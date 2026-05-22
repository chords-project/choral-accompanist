package org.choral.accompanist.faulttolerance;

import org.choral.accompanist.ReactiveServer;
import org.choral.accompanist.Session;
import org.choral.accompanist.connection.ClientConnectionsStore;
import org.choral.accompanist.connection.Message;
import org.choral.accompanist.tracing.JaegerConfiguration;
import org.choral.accompanist.tracing.TelemetrySession;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;

import java.sql.SQLException;
import java.time.Duration;

public class FaultTolerantServer extends ReactiveServer implements FaultServerConnectionManager.ServerEvents, FaultClientConnectionManager.ClientEvents {
    protected final FaultSessionEvent newFaultSessionEvent;
    protected final FaultDataStore dataStore;

    public FaultTolerantServer(FaultDataStore dataStore, FaultClientConnectionManager.Factory clientCon, FaultServerConnectionManager.Factory serverCon, String serviceName, OpenTelemetry telemetry, FaultSessionEvent newSessionEvent) {
        super(serviceName, null, null, telemetry, Duration.ofMinutes(10), null);
        this.connectionManager = serverCon.makeConnectionManager(serviceName, this, telemetry);
        this.clientConnectionsStore = new ClientConnectionsStore(clientCon.toNonFaultyFactory(this), telemetry);
        this.newFaultSessionEvent = newSessionEvent;
        this.dataStore = dataStore;
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
            Thread.ofVirtual().start(() -> {
                Span span = telemetry.getTracer(JaegerConfiguration.TRACER_NAME)
                        .spanBuilder("choreography session (recover)")
                        .setSpanKind(SpanKind.SERVER)
                        .setAttribute("choreography.session", session.toString())
                        .startSpan();

                var telemetrySession = new TelemetrySession(telemetry, session, span);

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

    @Override
    public void close() throws Exception {
        super.close();
        dataStore.close();
    }

    @Override
    protected Object startNewSession(TelemetrySession telemetrySession) throws Exception {
        try {
            Object result = super.startNewSession(telemetrySession);
            this.connectionManager().sessionCompleted(telemetrySession);
            return result;
        } catch (Exception e) {
            dataStore.restartSession(telemetrySession.session.sessionID());
            this.connectionManager().recoverableSessionFailure(telemetrySession);
            throw e;
        }
    }

    @Override
    protected Object runNewSessionEvent(TelemetrySession telemetrySession) throws Exception {
        var sessionID = telemetrySession.session.sessionID();
        dataStore.startSession(telemetrySession.session);
        try (FaultSessionContext sessionCtx = new FaultSessionContext(this, telemetrySession)) {
            Object result = newFaultSessionEvent.onNewSession(sessionCtx);
            dataStore.completeSession(sessionID);
            return result;
        } catch (ChoreographyInterruptedException e) {
            telemetrySession.log("Choreography interrupted: " + e.getMessage());
            dataStore.failSession(telemetrySession.session);
            dataStore.compensateTransactions(sessionID);
            return e;
        }
    }

    @Override
    public void sessionFailed(Session session) throws Exception {
        logger.info("Received session failed event for sessionID: " + session);
        try {
            dataStore.failSession(session);
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

        super.messageReceived(msg);
    }

    @Override
    public void messageDeliveryConfirmed(Message message) {

    }

    @Override
    public void messageDeliveryFailed(Message message) {
        try {
            dataStore.restartSession(message.session.sessionID());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
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
