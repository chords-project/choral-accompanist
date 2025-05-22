package dev.chords.warehouse.warehouse;

import choral.faulttolerance.FaultSessionContext;
import choral.faulttolerance.FaultTolerantServer;
import com.rabbitmq.client.Connection;

public class Warehouse implements FaultTolerantServer.FaultSessionEvent {

    public static void main(String[] args) throws Exception {
        var warehouse = new Warehouse();
        warehouse.start();
    }

    private FaultTolerantServer server;

    public Warehouse() throws Exception {
        Connection connection = null;
        server = new FaultTolerantServer(connection, "WAREHOUSE", this);
    }

    public void start() throws Exception {
        server.listen("localhost");
    }

    @Override
    public void onNewSession(FaultSessionContext ctx) {
        throw new IllegalStateException("Warehouse should never receive a NewSessionEvent");
    }
}
