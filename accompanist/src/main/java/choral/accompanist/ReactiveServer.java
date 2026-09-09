package choral.accompanist;

import choral.accompanist.channels.Future;
import choral.accompanist.connection.ClientConnectionManager;
import choral.accompanist.connection.ClientConnectionsStore;
import choral.accompanist.connection.Message;
import choral.accompanist.connection.ServerConnectionManager;
import choral.accompanist.tracing.AccompanistTelemetry;
import choral.accompanist.tracing.Logger;
import choral.accompanist.tracing.TelemetrySession;
import choral.accompanist.tracing.FaultToleranceTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.io.Serializable;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutionException;

public class ReactiveServer
        implements ServerConnectionManager.ServerEvents, ReactiveReceiver<Serializable>, AutoCloseable {

    protected final HashSet<Integer> knownSessionIDs = new HashSet<>();

    // INVARIANTS:
    // 1. sendQueue contains session if and only if recvQueue contains session.
    // 2. if sendQueue is non-empty, then recvQueue is empty; and vice versa.
    // 3. if a sessionID is in knownSessionIDs, then the session is in telemetrySessionMap.

    protected final MessageQueue<Serializable> msgQueue;

    /**
     * Maps a sessionID to a TelemetrySession.
     */
    protected final HashMap<Integer, TelemetrySession> telemetrySessionMap = new HashMap<>();

    public final String serviceName;
    protected final NewSessionEvent newSessionEvent;
    protected final OpenTelemetry telemetry;
    protected final Logger logger;
    protected ServerConnectionManager connectionManager;
    protected ClientConnectionsStore clientConnectionsStore;
    protected final DoubleHistogram receiveTimeHistogram;
    protected final DoubleHistogram sessionDurationHistogram;

    /**
     * Creates a ReactiveServer, using {@link ServerConnectionManager} for the connection.
     * Invoke {@link #listen(String)} to start listening.
     */
    public ReactiveServer(String serviceName, ServerConnectionManager connectionManager, ClientConnectionManager.Factory clientConnectionFactory, OpenTelemetry telemetry, Duration timeout, NewSessionEvent newSessionEvent) {
        this.serviceName = serviceName;
        this.telemetry = telemetry;
        this.logger = new Logger(telemetry, ReactiveServer.class.getName());
        this.newSessionEvent = newSessionEvent;
        this.connectionManager = connectionManager;
        this.clientConnectionsStore = new ClientConnectionsStore(clientConnectionFactory, telemetry);
        if (timeout != null) {
            this.msgQueue = new MessageQueue<>(timeout, telemetry);
        } else {
            this.msgQueue = new MessageQueue<>(telemetry);
        }
        this.receiveTimeHistogram = telemetry.getMeter(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME)
                .histogramBuilder("accompanist.server.receive-time")
                .setDescription("Channel receive time")
                .setUnit("ms")
                .build();
        this.sessionDurationHistogram = telemetry.getMeter(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME)
                .histogramBuilder("accompanist.server.session-duration")
                .setDescription("Session duration")
                .setUnit("ms")
                .build();
    }

    public ReactiveServer(String serviceName, ServerConnectionManager connectionManager, OpenTelemetry telemetry, NewSessionEvent newSessionEvent) {
        this(serviceName, connectionManager, ClientConnectionManager.defaultFactory(), telemetry, null, newSessionEvent);
    }

    /**
     * Creates a ReactiveServer, using {@link ServerConnectionManager#defaultFactory()} for the connection.
     * Invoke {@link #listen(String)} to start listening.
     */
    public ReactiveServer(String serviceName, OpenTelemetry telemetry, NewSessionEvent newSessionEvent) {
        this(serviceName, null, telemetry, newSessionEvent);
        this.connectionManager = ServerConnectionManager.defaultFactory().makeConnectionManager(serviceName, this, telemetry);
    }

    /**
     * Creates a ReactiveServer with telemetry disabled. Uses {@link ServerConnectionManager} for
     * the connection.
     */
    public ReactiveServer(String serviceName, NewSessionEvent newSessionEvent) {
        this(serviceName, OpenTelemetry.noop(), newSessionEvent);
    }

    /**
     * Begins listening at the given address and registers a shutdown hook that runs when the
     * program exits.
     */
    public void listen(String address) throws Exception {
        logger.info("Reactive server listening to " + address);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                ReactiveServer.this.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "ReactiveServer_SHUTDOWN_HOOK"));

        connectionManager.listen(address);
    }

    public ClientConnectionsStore getClientStore() {
        return clientConnectionsStore;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Serializable> Future<T> recv(Session session) {
        Attributes attributes = Attributes.builder()
                .put("channel.service", serviceName)
                .put("channel.sender", session.senderName())
                .put("channel.sessionID", session.sessionID)
                .build();

        Long startTime = System.nanoTime();

        Span span = telemetry.getTracer(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME)
                .spanBuilder("Receive message (" + session.senderName().toLowerCase() + ")")
                .setAllAttributes(attributes)
                .startSpan();

        TelemetrySession telemetrySession;
        synchronized (this) {
            telemetrySession = telemetrySessionMap.get(session.sessionID());
        }

        var future = msgQueue.retrieveMessage(session, telemetrySession);

        return () -> {
            try {
                T message = (T) future.get();
                span.setAttribute("message", message.toString());

                Long endTime = System.nanoTime();
                receiveTimeHistogram.record((endTime - startTime) / 1_000_000.0,
                        FaultToleranceTelemetry.metricAttributes(session, serviceName));

                return message;
            } catch (InterruptedException | ExecutionException e) {
                telemetrySession.recordException("ReactiveServer exception when receiving message", e, true, attributes);
                span.recordException(e);
                span.setAttribute("error", true);

                // It's the responsibility of the choreography to have the type cast match
                // Throw runtime exception if mismatch
                throw new RuntimeException(e);
            } finally {
                // End span on first call to .get()
                span.end();
            }
        };
    }

    @Override
    public <T extends Enum<T>> Future<T> recv_label(Session session) {
        return recv(session);
    }

    /**
     * Manually invokes a new session as though it was started by receiving a message with a new session.
     *
     * @param telemetrySession the new session. The session ID must be new and unique.
     * @throws Exception an exception thrown by the invoked choreography.
     */
    public Object invokeManualSession(TelemetrySession telemetrySession) throws Exception {
        var session = telemetrySession.session;
        telemetrySession.log(Severity.DEBUG, "Registering session", Attributes.empty());

        synchronized (this) {
            if (!knownSessionIDs.add(session.sessionID())) {
                throw new IllegalStateException("Session is already running: " + session.sessionID());
            }
            telemetrySessionMap.put(session.sessionID(), telemetrySession);
        }

        return startNewSession(telemetrySession);
    }

    public ReactiveChannel_B<Serializable> chanB(Session session, String clientName) {
        Session senderSession = session.replacingSender(clientName);

        TelemetrySession telemetrySession;
        synchronized (this) {
            if (!telemetrySessionMap.containsKey(senderSession.sessionID()))
                throw new IllegalStateException("Expected telemetrySessionMap to contain session: " + senderSession);

            telemetrySession = telemetrySessionMap.get(senderSession.sessionID());
        }

        return new ReactiveChannel_B<>(senderSession, this, telemetrySession);
    }

    @Override
    public synchronized void messageReceived(Message msg) {
        boolean isNewSession = knownSessionIDs.add(msg.session.sessionID);

        TelemetrySession telemetrySession;
        if (isNewSession) {
            telemetrySession = new TelemetrySession(telemetry, msg);
            this.telemetrySessionMap.put(msg.session.sessionID(), telemetrySession);
        } else {
            if (!telemetrySessionMap.containsKey(msg.session.sessionID()))
                throw new IllegalStateException(
                        "Expected telemetrySessionMap to contain session: " + msg.session);

            telemetrySession = telemetrySessionMap.get(msg.session.sessionID());
        }

        telemetrySession.log(Severity.DEBUG, "Reactive Server message received, new session: " + isNewSession, Attributes.empty());

        msgQueue.addMessage(msg.session, msg.message, msg.sequenceNumber, telemetrySession);

        if (isNewSession) {
            // Handle new session in another thread
            Thread.ofVirtual()
                    .name("NEW_SESSION_HANDLER_" + msg.session)
                    .start(() -> runSessionAsync(telemetrySession));
        }
    }

    /**
     * Runs a session whose exception cannot be returned to a caller.
     */
    protected void runSessionAsync(TelemetrySession telemetrySession) {
        try {
            startNewSession(telemetrySession);
        } catch (Exception alreadyLogged) {
            // startNewSession records the exception before rethrowing it.
        }
    }

    protected Object startNewSession(TelemetrySession telemetrySession) throws Exception {
        final Span span = telemetrySession.getChoreographySpan();

        Long startTime = System.nanoTime();
        var session = telemetrySession.session;
        synchronized (this) {
            this.telemetrySessionMap.put(session.sessionID(), telemetrySession);
        }

        telemetrySession.log(
                "ReactiveServer handle new session",
                Attributes.builder().put("service", serviceName).build());

        Object result = null;

        try (Scope scope = span.makeCurrent()) {
            result = runNewSessionEvent(telemetrySession);
            sessionExecutionCompleted(telemetrySession);
        } catch (Exception error) {
            try {
                sessionExecutionFailed(telemetrySession, error);
            } catch (Exception recoveryError) {
                error.addSuppressed(recoveryError);
            }
            telemetrySession.recordException(
                    "Choreography session execution failed",
                    error,
                    true,
                    Attributes.builder().put("service", serviceName).build());
            throw error;
        } finally {
            double durationMilliseconds = (System.nanoTime() - startTime) / 1_000_000.0;
            Attributes metricAttributes = FaultToleranceTelemetry.metricAttributes(session, serviceName);
            if (telemetrySession.isRootSpan())
                sessionDurationHistogram.record(durationMilliseconds, metricAttributes, Context.root().with(span));
            else
                sessionDurationHistogram.record(durationMilliseconds, metricAttributes);
            cleanupKey(telemetrySession);
            telemetrySession.close();
        }

        return result;
    }

    /**
     * Lifecycle hooks run before registration is released to another attempt.
     */
    protected void sessionExecutionCompleted(TelemetrySession telemetrySession) throws Exception {
    }

    protected void sessionExecutionFailed(TelemetrySession telemetrySession, Exception error) throws Exception {
    }

    protected Object runNewSessionEvent(TelemetrySession telemetrySession) throws Exception {
        Object result = null;
        try (SessionContext sessionCtx = new SessionContext(this, telemetrySession)) {
            result = newSessionEvent.onNewSession(sessionCtx);
        }
        return result;
    }

    protected synchronized void cleanupKey(TelemetrySession telemetrySession) {
        telemetrySession.log(Severity.DEBUG, "Cleaning up session", Attributes.empty());
        this.msgQueue.cleanupSession(telemetrySession.session);
        this.telemetrySessionMap.remove(telemetrySession.session.sessionID());
        this.knownSessionIDs.remove(telemetrySession.session.sessionID());
    }

    @Override
    public void close() throws Exception {
        logger.info("Shutting down reactive server");
        connectionManager.close();
    }

    public interface NewSessionEvent {
        /**
         * Event handler that is responsible for starting the choreography
         *
         * @param ctx the session context object for this session
         * @return a payload that is returned to {@link ReactiveServer#invokeManualSession}
         * @throws Exception the session is allowed to throw arbitrary exceptions
         */
        Object onNewSession(SessionContext ctx) throws Exception;
    }

    @Override
    public String toString() {
        return "ReactiveServer [serviceName=" + serviceName + "]";
    }
}
