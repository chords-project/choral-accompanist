package dev.chords.warehouse.loyalty;

import choral.faulttolerance.*;
import dev.chords.warehouse.choreograhpy.WarehouseOrder_Loyalty;

public class Loyalty implements FaultTolerantServer.FaultSessionEvent {

    public static void main(String[] args) throws Exception {
        var payment = new Loyalty();
        payment.start();
    }

    protected final FaultTolerantServer server;
    protected final LoyaltyService loyaltyService = new LoyaltyService();

    public final String SERVICE_NAME = "LOYALTY";
    public final String SERVER_ADDRESS = System.getenv("LOYALTY");

    public Loyalty() throws Exception {
        SQLDataStore dataStore = SQLDataStore.createHikariDataStore(
                "jdbc:postgresql://localhost:5432/warehouse_loyalty",
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
        var clientCon = MailboxFaultClientManager.factory(dataStore.db);
        var serverCon = MailboxFaultServerManager.factory(dataStore.db);

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
    public void onNewSession(FaultSessionContext ctx) {
        switch (ctx.session.choreographyName()) {
            case "WAREHOUSE_ORDER":
                WarehouseOrder_Loyalty chor = new WarehouseOrder_Loyalty(ctx, loyaltyService);
                chor.orderFulfillment();
                break;
            default:
                throw new IllegalStateException("Unexpected session choreography: " + ctx.session.choreographyName());
        }
    }
}
