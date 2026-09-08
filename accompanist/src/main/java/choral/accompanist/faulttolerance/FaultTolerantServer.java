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

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Semaphore;

public class FaultTolerantServer extends ReactiveServer implements FaultServerConnectionManager.ServerEvents, FaultClientConnectionManager.ClientEvents {
    protected final FaultSessionEvent newFaultSessionEvent;
    protected final FaultDataStore dataStore;
    protected final FaultToleranceTelemetry faultToleranceTelemetry;
    private final MailboxRecoveryCoordinator recoveryCoordinator;
    private final Semaphore recoveryLaunches;

    public FaultTolerantServer(FaultDataStore dataStore, FaultClientConnectionManager.Factory clientCon, FaultServerConnectionManager.Factory serverCon, String serviceName, OpenTelemetry telemetry, FaultSessionEvent newSessionEvent) {
        super(serviceName, null, null, telemetry, Duration.ofMinutes(10), null);
        this.connectionManager = serverCon.makeConnectionManager(serviceName, this, telemetry);
        this.clientConnectionsStore = new ClientConnectionsStore(clientCon.toNonFaultyFactory(this), telemetry);
        this.newFaultSessionEvent = newSessionEvent;
        this.dataStore = dataStore;
        this.faultToleranceTelemetry = new FaultToleranceTelemetry(telemetry, serviceName);
        var clientCoordinator = clientCon.recoveryCoordinator();
        var serverCoordinator = serverCon.recoveryCoordinator();
        this.recoveryCoordinator = clientCoordinator != null ? clientCoordinator : serverCoordinator;
        if (clientCoordinator != null && serverCoordinator != null && clientCoordinator != serverCoordinator)
            throw new IllegalArgumentException("Mailbox client and server must share one recovery coordinator");
        if (recoveryCoordinator != null) recoveryCoordinator.setReplayHandler(this::reconcileExecutions);
        this.recoveryLaunches = new Semaphore(recoveryCoordinator == null ? 8 : recoveryCoordinator.config().maxConcurrentReplays());
    }

    public FaultTolerantServer(FaultDataStore dataStore, FaultClientConnectionManager.Factory clientCon, FaultServerConnectionManager.Factory serverCon, String serviceName, FaultSessionEvent newSessionEvent) {
        this(dataStore, clientCon, serverCon, serviceName, OpenTelemetry.noop(), newSessionEvent);
    }

    public FaultServerConnectionManager connectionManager() {
        return (FaultServerConnectionManager) this.connectionManager;
    }

    @Override
    public void listen(String address) throws Exception {
        // Mailbox recovery starts after its listener is established. Other transports retain
        // their existing startup replay behavior.
        if (recoveryCoordinator == null) this.recoverStartedSessions();
        super.listen(address);
    }

    protected void recoverStartedSessions() throws SQLException {
        try {
            reconcileExecutions();
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("failed to recover sessions", e);
        }
    }

    /**
     * Replays failed (but recoverable) choreography sessions from the database.
     */
    void reconcileExecutions() throws Exception {
        int limit = recoveryCoordinator == null ? 128 : recoveryCoordinator.config().scanBatchSize();
        var candidates = dataStore.recoverableSessions(limit);
        // Compatibility with dynamic/mock implementations that predate this default API.
        if (candidates == null) candidates = dataStore.recoverStartedSessions().stream()
                .map(s -> new FaultDataStore.RecoverableSession(s, "started", null, null)).toList();
        for (var candidate : candidates) {
            if (candidate.waitingSender() != null && (recoveryCoordinator == null ||
                    !recoveryCoordinator.mailbox().hasReceived(candidate.session().sessionID(), candidate.waitingSender(), candidate.waitingSequence())))
                continue;
            launchRecovered(candidate.session());
        }
    }

    private void launchRecovered(Session session) throws Exception {
        if (!recoveryLaunches.tryAcquire()) return;
        Span span;
        TelemetrySession telemetrySession;
        synchronized (this) {
            if (!knownSessionIDs.add(session.sessionID())) {
                recoveryLaunches.release();
                return;
            }
            span = telemetry.getTracer(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME)
                    .spanBuilder("choreography session (recover)").setSpanKind(SpanKind.SERVER)
                    .setAllAttributes(TelemetrySession.commonAttributes(session)).startSpan();
            telemetrySession = new TelemetrySession(telemetry, session, span);
            telemetrySessionMap.put(session.sessionID(), telemetrySession);
        }
        try {
            if (recoveryCoordinator != null) {
                for (var message : recoveryCoordinator.mailbox().receivedMessages(session.sessionID()))
                    msgQueue.addMessage(message.session, message.message, message.sequenceNumber, telemetrySession);
            }
        } catch (Exception e) {
            cleanupKey(session);
            span.end();
            recoveryLaunches.release();
            throw e;
        }
        Thread.ofVirtual().name("RECOVER_SESSION_" + session.sessionID()).start(() -> {
            faultToleranceTelemetry.attempt(session, "recovery");
            try {
                startNewSession(telemetrySession);
            } catch (Exception e) {
                telemetrySession.recordException("failed to run recovered session", e, true);
            } finally {
                span.end();
                recoveryLaunches.release();
            }
        });
    }

    @Override
    public void close() throws Exception {
        super.close();
        if (recoveryCoordinator != null) recoveryCoordinator.close();
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
        if (changed)
            faultToleranceTelemetry.restart(telemetrySession.session, "retry");
        this.connectionManager().recoverableSessionFailure(telemetrySession);
    }

    @Override
    protected Object runNewSessionEvent(TelemetrySession telemetrySession) throws Exception {
        var sessionID = telemetrySession.session.sessionID();
        if (dataStore.startSession(telemetrySession.session))
            faultToleranceTelemetry.attempt(telemetrySession.session, "new");
        try (FaultSessionContext sessionCtx = new FaultSessionContext(this, telemetrySession)) {
            Object result = newFaultSessionEvent.onNewSession(sessionCtx);
            if (dataStore.completeSession(sessionID)) faultToleranceTelemetry.completion(telemetrySession.session);
            return result;
        } catch (ChoreographyInterruptedException e) {
            telemetrySession.log("Choreography interrupted: " + e.getMessage());
            if (dataStore.failSession(telemetrySession.session))
                faultToleranceTelemetry.failure(telemetrySession.session, "interrupted");
            dataStore.compensateTransactions(sessionID);
            return e;
        }
    }

    @Override
    public void sessionFailed(Session session) throws Exception {
        logger.info("Received session failed event for sessionID: " + session);
        try {
            if (dataStore.failSession(session)) faultToleranceTelemetry.failure(session, "remote");
            dataStore.compensateTransactions(session.sessionID());
        } catch (SQLException e) {
            logger.error("Session failed event caused SQL exception: " + e);
            throw e;
        }
    }

    @Override
    public void messageReceived(Message msg) {
        try {
            if (dataStore.hasSessionCompleted(msg.session.sessionID())) {
                logger.info("Received message with completed session: " + msg);
                return;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        boolean active;
        synchronized (this) {
            active = knownSessionIDs.contains(msg.session.sessionID());
        }
        if (recoveryCoordinator != null && !active) {
            // Receipt is already durable. Let the common eligibility/claim/hydration path
            // decide whether this exact input unblocks the parked session.
            try {
                reconcileExecutions();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return;
        }
        super.messageReceived(msg);
    }

    @Override
    public Object invokeManualSession(TelemetrySession telemetrySession) throws Exception {
        if (dataStore.hasSessionCompleted(telemetrySession.session.sessionID()))
            throw new IllegalStateException("Session is terminal: " + telemetrySession.session.sessionID());
        return super.invokeManualSession(telemetrySession);
    }

    @Override
    public void messageDeliveryConfirmed(Message message) {
        if (recoveryCoordinator != null) recoveryCoordinator.wake();
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
            if (recoveryCoordinator != null) recoveryCoordinator.wake();
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
