package choral.accompanist.faulttolerance;

import choral.accompanist.ReactiveServer;
import choral.accompanist.Session;
import choral.accompanist.connection.ClientConnectionsStore;
import choral.accompanist.connection.Message;
import choral.accompanist.tracing.AccompanistTelemetry;
import choral.accompanist.tracing.TelemetrySession;
import choral.accompanist.tracing.FaultToleranceTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;

public class FaultTolerantServer extends ReactiveServer implements FaultServerConnectionManager.ServerEvents, FaultClientConnectionManager.ClientEvents {
    public static final Duration DEFAULT_RECEIVE_TIMEOUT = Duration.ofSeconds(10);

    protected final FaultSessionEvent newFaultSessionEvent;
    protected final FaultDataStore dataStore;
    protected final FaultToleranceTelemetry faultToleranceTelemetry;
    private final MailboxRecoveryCoordinator recoveryCoordinator;
    private final Semaphore recoveryLaunches;

    public FaultTolerantServer(FaultDataStore dataStore, FaultClientConnectionManager.Factory clientCon, FaultServerConnectionManager.Factory serverCon, String serviceName, OpenTelemetry telemetry, FaultSessionEvent newSessionEvent) {
        super(serviceName, null, null, telemetry, receiveTimeout(System.getenv("ACCOMPANIST_RECEIVE_TIMEOUT_SECONDS")), null);
        this.connectionManager = serverCon.makeConnectionManager(serviceName, this, telemetry);
        this.clientConnectionsStore = new ClientConnectionsStore(clientCon.toNonFaultyFactory(this), telemetry);
        this.newFaultSessionEvent = newSessionEvent;
        this.dataStore = dataStore;
        this.faultToleranceTelemetry = new FaultToleranceTelemetry(telemetry, serviceName);
        var clientCoordinator = Objects.requireNonNull(clientCon.recoveryCoordinator(),
                "FaultTolerantServer requires the client transport to have a recovery coordinator");
        var serverCoordinator = Objects.requireNonNull(serverCon.recoveryCoordinator(),
                "FaultTolerantServer requires the server transport to have a recovery coordinator");
        if (clientCoordinator != serverCoordinator)
            throw new IllegalArgumentException("Mailbox client and server must share one recovery coordinator");
        this.recoveryCoordinator = clientCoordinator;
        recoveryCoordinator.configureTelemetry(telemetry, serviceName);
        recoveryCoordinator.setReplayHandler(this::reconcileExecutions);
        this.recoveryLaunches = new Semaphore(recoveryCoordinator.config().maxConcurrentReplays());
    }

    static Duration receiveTimeout(String configuredSeconds) {
        if (configuredSeconds == null || configuredSeconds.isBlank()) return DEFAULT_RECEIVE_TIMEOUT;
        try {
            long seconds = Long.parseLong(configuredSeconds);
            return seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_RECEIVE_TIMEOUT;
        } catch (NumberFormatException ignored) {
            return DEFAULT_RECEIVE_TIMEOUT;
        }
    }

    public FaultTolerantServer(FaultDataStore dataStore, FaultClientConnectionManager.Factory clientCon, FaultServerConnectionManager.Factory serverCon, String serviceName, FaultSessionEvent newSessionEvent) {
        this(dataStore, clientCon, serverCon, serviceName, OpenTelemetry.noop(), newSessionEvent);
    }

    public FaultServerConnectionManager connectionManager() {
        return (FaultServerConnectionManager) this.connectionManager;
    }

    /**
     * Replays failed (but recoverable) choreography sessions from the database.
     */
    void reconcileExecutions() throws Exception {
        var candidates = dataStore.recoverableSessions(recoveryCoordinator.config().scanBatchSize());
        for (var candidate : candidates) {
            if (candidate.waitingSender() != null &&
                    !recoveryCoordinator.mailbox().hasReceived(candidate.session().sessionID(), candidate.waitingSender(), candidate.waitingSequence()))
                continue;
            launchReconciled(candidate);
        }
    }

    private void launchReconciled(FaultDataStore.RecoverableSession candidate) {
        Session session = candidate.session();
        if (!recoveryLaunches.tryAcquire()) return;
        Span span;
        TelemetrySession telemetrySession;
        synchronized (this) {
            if (!knownSessionIDs.add(session.sessionID())) {
                recoveryLaunches.release();
                return;
            }
            var spanBuilder = telemetry.getTracer(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME)
                    .spanBuilder(candidate.isRestart() ? "choreography session (recover)" : "choreography session")
                    .setSpanKind(SpanKind.SERVER).setAllAttributes(TelemetrySession.commonAttributes(session));
            boolean hasParent = candidate.traceContext() != null && candidate.traceContext().isValid();
            if (hasParent)
                spanBuilder.setParent(Context.root().with(Span.wrap(candidate.traceContext())));
            else
                spanBuilder.setNoParent();
            span = spanBuilder.startSpan();
            telemetrySession = new TelemetrySession(telemetry, session, span,
                    candidate.isRestart() ? TelemetrySession.AttemptKind.RECOVERY : TelemetrySession.AttemptKind.NEW,
                    !hasParent);
            telemetrySessionMap.put(session.sessionID(), telemetrySession);
        }
        try {
            for (var message : recoveryCoordinator.mailbox().receivedMessages(session.sessionID()))
                msgQueue.addMessage(message.session, message.message, message.sequenceNumber, telemetrySession);
        } catch (Exception e) {
            telemetrySession.recordException("Failed to hydrate recovered session", e, true);
            cleanupKey(telemetrySession);
            telemetrySession.close();
            recoveryLaunches.release();
            return;
        }
        Thread.ofVirtual().name("RECOVER_SESSION_" + session.sessionID()).start(() -> {
            try {
                runSessionAsync(telemetrySession);
            } finally {
                recoveryLaunches.release();
            }
        });
    }

    @Override
    public void close() throws Exception {
        super.close();
        recoveryCoordinator.close();
        dataStore.close();
    }

    @Override
    protected void sessionExecutionCompleted(TelemetrySession telemetrySession) throws Exception {
        this.connectionManager().sessionCompleted(telemetrySession);
    }

    @Override
    protected void sessionExecutionFailed(TelemetrySession telemetrySession, Exception error) throws Exception {
        ReceiveTimeoutException timeout = findReceiveTimeout(error);
        boolean changed = timeout == null
                ? dataStore.restartSession(telemetrySession.session.sessionID())
                : dataStore.restartSession(telemetrySession.session.sessionID(), timeout.sender(), timeout.sequenceNumber());
        if (changed) {
            faultToleranceTelemetry.restart(telemetrySession.session, "retry");
            telemetrySession.log("Marked session for recovery");
        }
        this.connectionManager().recoverableSessionFailure(telemetrySession);
    }

    @Override
    protected Object runNewSessionEvent(TelemetrySession telemetrySession) throws Exception {
        var sessionID = telemetrySession.session.sessionID();
        if (dataStore.startSession(telemetrySession.session, telemetrySession.spanContext())) {
            faultToleranceTelemetry.attempt(telemetrySession.session, telemetrySession.attemptKind().metricValue());
            telemetrySession.log("Started session attempt");
        }
        try (FaultSessionContext sessionCtx = new FaultSessionContext(this, telemetrySession)) {
            Object result = newFaultSessionEvent.onNewSession(sessionCtx);
            if (dataStore.completeSession(sessionID)) {
                faultToleranceTelemetry.completion(telemetrySession.session);
                telemetrySession.log("Completed session");
            }
            return result;
        } catch (ChoreographyInterruptedException e) {
            telemetrySession.log("Choreography interrupted: " + e.getMessage());
            if (dataStore.failSession(telemetrySession.session))
                faultToleranceTelemetry.failure(telemetrySession.session, "interrupted");
            dataStore.compensateTransactions(sessionID);
            telemetrySession.log("Compensated session transactions");
            return e;
        }
    }

    @Override
    public void sessionFailed(TelemetrySession telemetrySession) throws Exception {
        Session session = telemetrySession.session;
        telemetrySession.log("Received remote session failure");
        try {
            if (dataStore.failSession(session)) faultToleranceTelemetry.failure(session, "remote");
            dataStore.compensateTransactions(session.sessionID());
            telemetrySession.log("Compensated session transactions");
        } catch (SQLException e) {
            telemetrySession.recordException("Session failure handling caused SQL exception", e, true);
            throw e;
        }
    }

    @Override
    public void messageReceived(Message msg) {
        try {
            if (dataStore.hasSessionCompleted(msg.session.sessionID())) {
                try (var completedSession = new TelemetrySession(telemetry, msg)) {
                    completedSession.log("Ignored message for completed session");
                }
                return;
            }
        } catch (SQLException e) {
            recordIncomingException(msg, "Failed to look up session state", e);
            throw new RuntimeException(e);
        }

        boolean active;
        synchronized (this) {
            active = knownSessionIDs.contains(msg.session.sessionID());
        }
        if (!active) {
            // Receipt is already durable. Let the common eligibility/claim/hydration path
            // decide whether this exact input unblocks the parked session.
            try {
                reconcileExecutions();
            } catch (Exception e) {
                recordIncomingException(msg, "Failed to reconcile session execution", e);
                throw new RuntimeException(e);
            }
            return;
        }
        super.messageReceived(msg);
    }

    private void recordIncomingException(Message message, String description, Exception error) {
        try (var incomingSession = new TelemetrySession(telemetry, message)) {
            incomingSession.recordException(description, error, true);
        }
    }

    @Override
    public Object invokeManualSession(TelemetrySession telemetrySession) throws Exception {
        if (dataStore.hasSessionCompleted(telemetrySession.session.sessionID()))
            throw new IllegalStateException("Session is terminal: " + telemetrySession.session.sessionID());
        return super.invokeManualSession(telemetrySession);
    }

    @Override
    public void messageDeliveryConfirmed(Message message) {
        recoveryCoordinator.wake();
    }

    @Override
    public void messageDeliveryFailed(Message message) {
        // Delivery is tracked by the pending outbox row. A late transport callback
        // must not invalidate an active execution or reopen a completed session.
    }

    @Override
    protected Object startNewSession(TelemetrySession telemetrySession) throws Exception {
        try {
            return super.startNewSession(telemetrySession);
        } finally {
            recoveryCoordinator.wake();
        }
    }

    private static ReceiveTimeoutException findReceiveTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause())
            if (current instanceof ReceiveTimeoutException timeout) return timeout;
        return null;
    }

    @Override
    public String toString() {
        return "FaultTolerantServer [serviceName=" + serviceName + "]";
    }

    /**
     * This interface is the fault-tolerant equivalent to {@link ReactiveServer.NewSessionEvent}
     */
    public interface FaultSessionEvent {
        /**
         * Event handler that is responsible for starting the choreography
         */
        Object onNewSession(FaultSessionContext ctx) throws Exception;
    }
}
