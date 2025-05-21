package choral.reactive;

import choral.channels.Future;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.connection.ClientConnectionsStore;
import choral.reactive.connection.Message;
import choral.reactive.connection.ServerConnectionManager;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import choral.reactive.tracing.TelemetrySession;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
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
    protected final ClientConnectionsStore clientConnectionsStore;
    protected final DoubleHistogram receiveTimeHistogram;
    protected final DoubleHistogram sessionDurationHistogram;

    /**
     * Creates a ReactiveServer, using {@link ServerConnectionManager} for the connection.
     * Invoke {@link #listen(String)} to start listening.
     */
    public ReactiveServer(String serviceName, ServerConnectionManager connectionManager, OpenTelemetry telemetry, Duration timeout, NewSessionEvent newSessionEvent) {
        this.serviceName = serviceName;
        this.telemetry = telemetry;
        this.logger = new Logger(telemetry, ReactiveServer.class.getName());
        this.newSessionEvent = newSessionEvent;
        this.connectionManager = connectionManager;
        this.clientConnectionsStore = new ClientConnectionsStore(telemetry);
        if (timeout != null) {
            this.msgQueue = new MessageQueue<>(timeout, telemetry);
        } else {
            this.msgQueue = new MessageQueue<>(telemetry);
        }
        this.receiveTimeHistogram = telemetry.getMeter(JaegerConfiguration.TRACER_NAME)
                .histogramBuilder("choral.reactive.server.receive-time")
                .setDescription("Channel receive time")
                .setUnit("ms")
                .build();
        this.sessionDurationHistogram = telemetry.getMeter(JaegerConfiguration.TRACER_NAME)
                .histogramBuilder("choral.reactive.server.session-duration")
                .setDescription("Session duration")
                .setUnit("ms")
                .build();
    }

    public ReactiveServer(String serviceName, ServerConnectionManager connectionManager, OpenTelemetry telemetry, NewSessionEvent newSessionEvent) {
        this(serviceName, connectionManager, telemetry, null, newSessionEvent);
    }

    /**
     * Creates a ReactiveServer, using {@link ServerConnectionManager#makeConnectionManager} for the connection.
     * Invoke {@link #listen(String)} to start listening.
     */
    public ReactiveServer(String serviceName, OpenTelemetry telemetry, NewSessionEvent newSessionEvent) {
        this(serviceName, null, telemetry, newSessionEvent);
        this.connectionManager = ServerConnectionManager.makeConnectionManager(this, telemetry);
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

        Span span = telemetry.getTracer(JaegerConfiguration.TRACER_NAME)
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
                receiveTimeHistogram.record((endTime - startTime) / 1_000_000.0, attributes);

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

    public void registerSession(Session session, TelemetrySession telemetrySession) {
        logger.debug("Registering session " + session.sessionID);

        synchronized (this) {
            knownSessionIDs.add(session.sessionID());
            telemetrySessionMap.put(session.sessionID(), telemetrySession);
        }
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
    public void messageReceived(Message msg) {

        synchronized (this) {
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
                        .start(() -> {
                            try {
                                startNewSession(msg, telemetrySession);
                            } catch (Exception e) {
                                telemetrySession.recordException(
                                        "ReactiveServer session exception",
                                        e,
                                        true,
                                        Attributes.builder().put("service", serviceName)
                                                .put("session", msg.session.toString()).build());
                            }
                        });
            }
        }
    }

    protected void startNewSession(Message msg, TelemetrySession telemetrySession) throws Exception {
        final Span span = telemetrySession.makeChoreographySpan();

        Long startTime = System.nanoTime();
        this.telemetrySessionMap.put(msg.session.sessionID(), telemetrySession);

        telemetrySession.log(
                "ReactiveServer handle new session",
                Attributes.builder().put("service", serviceName).build());

        try (Scope scope = span.makeCurrent()) {
            runNewSessionEvent(telemetrySession);
        } finally {
            span.end();
        }

        cleanupKey(msg.session);
        Long endTime = System.nanoTime();
        sessionDurationHistogram.record(
                (endTime - startTime) / 1_000_000.0,
                Attributes.builder().put("session", msg.session.toString()).build()
        );
    }

    protected void runNewSessionEvent(TelemetrySession telemetrySession) throws Exception {
        try (SessionContext sessionCtx = new SessionContext(this, telemetrySession)) {
            newSessionEvent.onNewSession(sessionCtx);
        }
    }

    protected void cleanupKey(Session session) {
        logger.debug("Cleaning up session " + session.sessionID);

        synchronized (this) {
            this.msgQueue.cleanupSession(session);
            this.telemetrySessionMap.remove(session.sessionID());
            this.knownSessionIDs.remove(session.sessionID());
        }
    }

    @Override
    public void close() throws Exception {
        logger.info("Shutting down reactive server");
        connectionManager.close();
    }

    public interface NewSessionEvent {
        void onNewSession(SessionContext ctx) throws Exception;
    }

    @Override
    public String toString() {
        return "ReactiveServer [serviceName=" + serviceName + "]";
    }
}
