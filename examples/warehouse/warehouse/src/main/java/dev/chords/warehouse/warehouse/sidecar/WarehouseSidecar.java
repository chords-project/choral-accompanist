package dev.chords.warehouse.warehouse.sidecar;

import choral.reactive.Session;
import choral.reactive.tracing.TelemetrySession;
import dev.chords.warehouse.choreograhpy.WarehouseOrder_Warehouse;
import io.opentelemetry.api.OpenTelemetry;

public class WarehouseSidecar implements FaultTolerantServer.FaultSessionEvent, RestEndpoint.Events {

    public static void main(String[] args) throws Exception {
        var warehouse = new WarehouseSidecar();
        warehouse.start();
    }

    public static final String SERVICE_NAME = "WAREHOUSE";
    public static final String SERVER_ADDRESS = System.getenv("WAREHOUSE");

    protected final FaultTolerantServer server;
    protected final WarehouseTransactions warehouseTransactions;
    protected final RestEndpoint endpoint;

    public WarehouseSidecar() throws Exception {
        //final var telemetry = LocalConfiguration.initTelemetry(SERVICE_NAME);
        final var telemetry = OpenTelemetry.noop();

        warehouseTransactions = new DirectTransactions();

        var dbUrl = System.getenv().getOrDefault("POSTGRES_URL", "postgresql://localhost:5432/warehouse_warehouse");

        SQLDataStore dataStore = SQLDataStore.createHikariDataStore(
                "jdbc:" + dbUrl,
                "postgres",
                "postgres",
                warehouseTransactions.allTransactions()
        );

        // RabbitMQ connection
//        var connectionFactory = new ConnectionFactory();
//        connectionFactory.setHost(RMQ_ADDRESS);
//        var connection = connectionFactory.newConnection();
//        var clientCon = RMQChannelSender.factory(connection);
//        var serverCon = RMQChannelReceiver.factory();

        // Mailbox connection
        String[] broadcastClients = {System.getenv("PAYMENT"), System.getenv("LOYALTY")};
        var clientCon = MailboxFaultClientManager.factory(dataStore.db);
        var serverCon = MailboxFaultServerManager.factory(dataStore.db, broadcastClients);

        server = new FaultTolerantServer(dataStore, clientCon, serverCon, SERVICE_NAME, telemetry, this);

        endpoint = new RestEndpoint(this);
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

        endpoint.start();

        // Thread.sleep(1000);
        // orderFulfillment();

        serverThread.join();
    }

    @Override
    public Object onNewSession(FaultSessionContext ctx) {
        switch (ctx.session.choreographyName()) {
            case "WAREHOUSE_ORDER":
                var t1 = System.nanoTime();
                WarehouseOrder_Warehouse chor = new WarehouseOrder_Warehouse(ctx, warehouseTransactions);
                chor.orderFulfillment();
                var t2 = System.nanoTime();
                return "run choreography: order %d processed in %s ms".formatted(ctx.session.sessionID(), (t2 - t1) / 1000000.0);
            default:
                throw new IllegalStateException("Unexpected session choreography: " + ctx.session.choreographyName());
        }
    }

    @Override
    public Object orderFulfillment() throws Exception {
        Session session = Session.makeSession("WAREHOUSE_ORDER", SERVICE_NAME);
        TelemetrySession telemetrySession = new TelemetrySession(session);

        return server.invokeManualSession(telemetrySession);
    }
}
