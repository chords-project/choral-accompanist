package org.choral.accompanist.faulttolerance;

import org.choral.accompanist.SessionContext;
import org.choral.accompanist.tracing.TelemetrySession;

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

        boolean transactionSucccess = false;

        try {
            transactionSucccess = dataStore.commitTransaction(session.sessionID(), trans);
        } catch (SQLException e) {
            telemetrySession.recordException("transaction commit failed", e, false);
        }

        if (!transactionSucccess) {
            try {
                server().connectionManager().broadcastSessionFailure(telemetrySession);
            } catch (Exception e) {
                telemetrySession.recordException("could not broadcast session failure", e, true);
            }

            throw new ChoreographyInterruptedException("Transaction aborted: " + trans.transactionName());
        }
    }
}
