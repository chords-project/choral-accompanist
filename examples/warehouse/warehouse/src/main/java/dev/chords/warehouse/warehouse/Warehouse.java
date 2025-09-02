package dev.chords.warehouse.warehouse;

import choral.faulttolerance.*;
import choral.reactive.Session;
import choral.reactive.tracing.TelemetrySession;
import dev.chords.warehouse.choreograhpy.WarehouseOrder_Warehouse;

public class Warehouse implements FaultTolerantServer.FaultSessionEvent {

    public static void main(String[] args) throws Exception {
        var warehouse = new Warehouse();
        warehouse.start();
    }

    public static final String SERVICE_NAME = "WAREHOUSE";
    public static final String SERVER_ADDRESS = System.getenv("WAREHOUSE");

    protected final FaultTolerantServer server;
    protected final WarehouseService warehouseService;

    public Warehouse() throws Exception {
        warehouseService = new WarehouseService();

        SQLDataStore dataStore = SQLDataStore.createHikariDataStore(
                "jdbc:postgresql://localhost:5432/warehouse_warehouse",
                "postgres",
                "postgres",
                warehouseService.allTransactions()
        );

        // RabbitMQ connection
//        var connectionFactory = new ConnectionFactory();
//        connectionFactory.setHost(RMQ_ADDRESS);
//        var connection = connectionFactory.newConnection();
//        var clientCon = RMQChannelSender.factory(connection);
//        var serverCon = RMQChannelReceiver.factory();

        // Mailbox connection
        var clientCon = MailboxFaultClientManager.factory(dataStore.db);
        var serverCon = MailboxFaultServerManager.factory(dataStore.db);

        server = new FaultTolerantServer(dataStore, clientCon, serverCon, SERVICE_NAME, this);
    }

    public void start() throws Exception {
        var serverThread = Thread.ofVirtual().start(() -> {
            try {
                System.out.println("Starting warehouse on address " + SERVER_ADDRESS);
                server.listen(SERVER_ADDRESS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(1000);

        orderFulfillment();

        serverThread.join();
    }

    @Override
    public void onNewSession(FaultSessionContext ctx) {
        switch (ctx.session.choreographyName()) {
            case "WAREHOUSE_ORDER":
                WarehouseOrder_Warehouse chor = new WarehouseOrder_Warehouse(ctx, warehouseService);
                chor.orderFulfillment();
                break;
            default:
                throw new IllegalStateException("Unexpected session choreography: " + ctx.session.choreographyName());
        }
    }

    public void orderFulfillment() throws Exception {
        Session session = Session.makeSession("WAREHOUSE_ORDER", SERVICE_NAME);
        TelemetrySession telemetrySession = new TelemetrySession(session);

        server.invokeManualSession(telemetrySession);
    }
}
