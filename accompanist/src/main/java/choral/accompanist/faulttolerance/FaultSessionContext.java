package choral.accompanist.faulttolerance;

import choral.accompanist.SessionContext;
import choral.accompanist.tracing.TelemetrySession;
import io.opentelemetry.api.common.Attributes;

import java.sql.SQLException;

public class FaultSessionContext extends SessionContext {

    public FaultSessionContext(FaultTolerantServer server, TelemetrySession telemetrySession) {
        super(server, telemetrySession);
    }

    public FaultTolerantServer server() {
        return (FaultTolerantServer) server;
    }

    public void transaction(Transaction trans) {
        var dataStore = server().dataStore;

        boolean transactionSuccess = false;

        Attributes attributes = Attributes.builder()
                .put("transaction.name", trans.transactionName())
                .build();

        try {
            transactionSuccess = dataStore.commitTransaction(session.sessionID(), trans);
            telemetrySession.log("Transaction commit " + (transactionSuccess ? "completed" : "failed") + ": " + trans.transactionName(), attributes);
        } catch (SQLException e) {
            telemetrySession.recordException("transaction commit failed: " + trans.transactionName(), e, false, attributes);
        }

        if (!transactionSuccess) {
            try {
                server().connectionManager().broadcastSessionFailure(telemetrySession);
            } catch (Exception e) {
                telemetrySession.recordException("could not broadcast session failure", e, true);
            }

            throw new ChoreographyInterruptedException("Transaction aborted: " + trans.transactionName());
        }
    }
}
