package choral.faulttolerance;

import choral.reactive.ReactiveChannel_A;
import choral.reactive.ReactiveClient;
import choral.reactive.ReactiveSymChannel;
import choral.reactive.SessionContext;
import choral.reactive.tracing.TelemetrySession;

import java.io.Serializable;
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
            transactionSucccess = dataStore.commitTransaction(session, trans);
        } catch (SQLException e) {
            telemetrySession.recordException("transaction commit failed", e, false);
        }

        try {
            if (!transactionSucccess) {
                dataStore.failSession(session);
            }

            // TODO: Compensate all other transactions

        } catch (SQLException e) {
            telemetrySession.recordException("could not mark session as 'failed'", e, true);
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
