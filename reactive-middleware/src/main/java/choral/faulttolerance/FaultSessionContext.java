package choral.faulttolerance;

import choral.reactive.ReactiveChannel_A;
import choral.reactive.ReactiveClient;
import choral.reactive.ReactiveSymChannel;
import choral.reactive.SessionContext;
import choral.reactive.tracing.TelemetrySession;

import java.io.IOException;
import java.io.Serializable;
import java.sql.SQLException;
import java.util.concurrent.TimeoutException;

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
                server().connectionManager().broadcastSessionFailure(session.sessionID());
            } catch (IOException | InterruptedException | TimeoutException e) {
                telemetrySession.recordException("could not broadcast session failure", e, true);
            }

            throw new ChoreographyInterruptedException("Transaction aborted: " + trans.transactionName());
        }
    }

    @Override
    public ReactiveChannel_A<Serializable> chanA(String receiverServiceName) throws Exception {
        var sender = new RMQChannelSender(server().connectionManager().connection, receiverServiceName);
        var client = new ReactiveClient(
                sender,
                server.serviceName,
                telemetrySession);

        closeHandles.add(client);

        return new ReactiveChannel_A<>(session, client, telemetrySession);
    }

    public ReactiveSymChannel<Serializable> symChan(String receiverServiceName)
            throws Exception {
        var a = chanA(receiverServiceName);
        var b = chanB(receiverServiceName);
        return new ReactiveSymChannel<>(a, b);
    }
}
