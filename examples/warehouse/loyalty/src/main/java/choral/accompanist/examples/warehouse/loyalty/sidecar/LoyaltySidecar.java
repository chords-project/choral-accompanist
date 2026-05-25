package choral.accompanist.examples.warehouse.loyalty.sidecar;

import choral.accompanist.examples.warehouse.choreograhpy.WarehouseOrder_Loyalty;
import choral.accompanist.faulttolerance.*;

public class LoyaltySidecar implements FaultTolerantServer.FaultSessionEvent {

    public static void main(String[] args) throws Exception {
        var payment = new LoyaltySidecar();
        payment.start();
    }

    protected final FaultTolerantServer server;
    protected final LoyaltyTransactions loyaltyService = new SidecarTransactions();

    public final String SERVICE_NAME = "LOYALTY";
    public final String SERVER_ADDRESS = System.getenv("LOYALTY");

    public LoyaltySidecar() throws Exception {
        var dbUrl = System.getenv().getOrDefault("POSTGRES_URL", "postgresql://localhost:5432/warehouse_loyalty");

        SQLDataStore dataStore = SQLDataStore.createHikariDataStore(
                "jdbc:" + dbUrl,
                "postgres",
                "postgres",
                loyaltyService.allTransactions());

        // RabbitMQ connection
//        var connectionFactory = new ConnectionFactory();
//        connectionFactory.setHost(RMQ_ADDRESS);
//        var connection = connectionFactory.newConnection();
//        var clientCon = RMQChannelSender.factory(connection);
//        var serverCon = RMQChannelReceiver.factory();

        // Mailbox connection
        String[] broadcastClients = {System.getenv("WAREHOUSE"), System.getenv("PAYMENT")};
        var clientCon = MailboxFaultClientManager.factory(dataStore.db);
        var serverCon = MailboxFaultServerManager.factory(dataStore.db, broadcastClients);

        server = new FaultTolerantServer(dataStore, clientCon, serverCon, SERVICE_NAME, this);

        try (var con = dataStore.db.getConnection()) {
            loyaltyService.createTables(con);
        }
    }

    public void start() throws Exception {
        System.out.println("Starting Loyalty on address " + SERVER_ADDRESS);
        server.listen(SERVER_ADDRESS);
    }

    @Override
    public Object onNewSession(FaultSessionContext ctx) {
        switch (ctx.session.choreographyName()) {
            case "WAREHOUSE_ORDER":
                WarehouseOrder_Loyalty chor = new WarehouseOrder_Loyalty(ctx, loyaltyService);
                chor.orderFulfillment();
                return null;
            default:
                throw new IllegalStateException("Unexpected session choreography: " + ctx.session.choreographyName());
        }
    }
}
