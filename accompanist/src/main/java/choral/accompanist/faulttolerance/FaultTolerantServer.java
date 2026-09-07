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

public class FaultTolerantServer extends ReactiveServer implements FaultServerConnectionManager.ServerEvents, FaultClientConnectionManager.ClientEvents {
    protected final FaultSessionEvent newFaultSessionEvent;
    protected final FaultDataStore dataStore;
    protected final FaultToleranceTelemetry faultToleranceTelemetry;

    public FaultTolerantServer(FaultDataStore dataStore, FaultClientConnectionManager.Factory clientCon, FaultServerConnectionManager.Factory serverCon, String serviceName, OpenTelemetry telemetry, FaultSessionEvent newSessionEvent) {
        super(serviceName, null, null, telemetry, Duration.ofMinutes(10), null);
        this.connectionManager = serverCon.makeConnectionManager(serviceName, this, telemetry);
        this.clientConnectionsStore = new ClientConnectionsStore(clientCon.toNonFaultyFactory(this), telemetry);
        this.newFaultSessionEvent = newSessionEvent;
        this.dataStore = dataStore;
        this.faultToleranceTelemetry = new FaultToleranceTelemetry(telemetry, serviceName);
    }

    public FaultTolerantServer(FaultDataStore dataStore, FaultClientConnectionManager.Factory clientCon, FaultServerConnectionManager.Factory serverCon, String serviceName, FaultSessionEvent newSessionEvent) {
        this(dataStore, clientCon, serverCon, serviceName, OpenTelemetry.noop(), newSessionEvent);
    }

    public FaultServerConnectionManager connectionManager() {
        return (FaultServerConnectionManager) this.connectionManager;
    }

    @Override
    public void listen(String address) throws Exception {
        this.recoverStartedSessions();
        super.listen(address);
    }

    protected void recoverStartedSessions() throws SQLException {
        var pendingSessions = this.dataStore.recoverStartedSessions();
        for (var session : pendingSessions) {
            synchronized (this) {
                if (!knownSessionIDs.add(session.sessionID())) continue;
                Span span = telemetry.getTracer(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME)
                        .spanBuilder("choreography session (recover)")
                        .setSpanKind(SpanKind.SERVER)
                        .setAllAttributes(TelemetrySession.commonAttributes(session))
                        .startSpan();

                var telemetrySession = new TelemetrySession(telemetry, session, span);
                telemetrySessionMap.put(session.sessionID(), telemetrySession);
                Thread.ofVirtual().start(() -> {
                    faultToleranceTelemetry.attempt(session, "recovery");
                    try {
                        startNewSession(telemetrySession);
                    } catch (Exception e) {
                        telemetrySession.recordException("failed to run recovered session", e, true);
                    } finally {
                        span.end();
                    }
                });
            }
        }
    }

    @Override
    public void close() throws Exception {
        super.close();
        dataStore.close();
    }

    @Override
    protected void sessionExecutionCompleted(TelemetrySession telemetrySession) throws Exception {
        this.connectionManager().sessionCompleted(telemetrySession);
    }

    @Override
    protected void sessionExecutionFailed(TelemetrySession telemetrySession, Exception error) throws Exception {
        if (dataStore.restartSession(telemetrySession.session.sessionID()))
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
    public synchronized void messageReceived(Message msg) {
        try {
            if (dataStore.hasSessionCompleted(msg.session.sessionID())) {
                logger.info("Received message with completed session: " + msg);
                return;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        super.messageReceived(msg);
    }

    @Override
    public void messageDeliveryConfirmed(Message message) {

    }

    @Override
    public void messageDeliveryFailed(Message message) {
        // Delivery is tracked by the pending outbox row. A late transport callback
        // must not invalidate an active execution or reopen a completed session.
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
