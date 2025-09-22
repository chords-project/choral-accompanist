package dev.chords.warehouse.payment;

import choral.faulttolerance.*;
import dev.chords.warehouse.choreograhpy.WarehouseOrder_Payment;

public class Payment implements FaultTolerantServer.FaultSessionEvent {

    public static void main(String[] args) throws Exception {
        var payment = new Payment();
        payment.start();
    }

    private final FaultTolerantServer server;
    private final PaymentService paymentService;

    public static final String SERVICE_NAME = "PAYMENT";
    public static final String SERVER_ADDRESS = System.getenv("PAYMENT");

    public Payment() throws Exception {
        paymentService = new PaymentService();

        var dbUrl = System.getenv().getOrDefault("POSTGRES_URL", "postgresql://localhost:5432/warehouse_payment");

        SQLDataStore dataStore = SQLDataStore.createHikariDataStore(
                "jdbc:" + dbUrl,
                "postgres",
                "postgres",
                paymentService.allTransactions()
        );

        // RabbitMQ connection
//        var connectionFactory = new ConnectionFactory();
//        connectionFactory.setHost(RMQ_ADDRESS);
//        var connection = connectionFactory.newConnection();
//        var clientCon = RMQChannelSender.factory(connection);
//        var serverCon = RMQChannelReceiver.factory();

        // Mailbox connection
        String[] broadcastClients = {System.getenv("WAREHOUSE"), System.getenv("LOYALTY")};
        var clientCon = MailboxFaultClientManager.factory(dataStore.db);
        var serverCon = MailboxFaultServerManager.factory(dataStore.db, broadcastClients);

        server = new FaultTolerantServer(dataStore, clientCon, serverCon, SERVICE_NAME, this);
    }

    public void start() throws Exception {
        System.out.println("Starting payment on address: " + SERVER_ADDRESS);
        server.listen(SERVER_ADDRESS);
    }

    @Override
    public void onNewSession(FaultSessionContext ctx) {
        switch (ctx.session.choreographyName()) {
            case "WAREHOUSE_ORDER":
                WarehouseOrder_Payment chor = new WarehouseOrder_Payment(ctx, paymentService);
                chor.orderFulfillment();
                break;
            default:
                throw new IllegalStateException("Unexpected session choreography: " + ctx.session.choreographyName());
        }
    }
}
