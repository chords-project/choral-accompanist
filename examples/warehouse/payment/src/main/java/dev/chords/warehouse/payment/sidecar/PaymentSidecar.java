package dev.chords.warehouse.payment.sidecar;

import dev.chords.warehouse.choreograhpy.WarehouseOrder_Payment;

public class PaymentSidecar implements FaultTolerantServer.FaultSessionEvent {

    public static void main(String[] args) throws Exception {
        var payment = new PaymentSidecar();
        payment.start();
    }

    private final FaultTolerantServer server;
    private final PaymentTransactions paymentTransactions;

    public static final String SERVICE_NAME = "PAYMENT";
    public static final String SERVER_ADDRESS = System.getenv("PAYMENT");

    public PaymentSidecar() throws Exception {
        paymentTransactions = new SidecarTransactions();

        var dbUrl = System.getenv().getOrDefault("POSTGRES_URL", "postgresql://localhost:5432/warehouse_payment");

        SQLDataStore dataStore = SQLDataStore.createHikariDataStore(
                "jdbc:" + dbUrl,
                "postgres",
                "postgres",
                paymentTransactions.allTransactions()
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
    public Object onNewSession(FaultSessionContext ctx) {
        switch (ctx.session.choreographyName()) {
            case "WAREHOUSE_ORDER":
                WarehouseOrder_Payment chor = new WarehouseOrder_Payment(ctx, paymentTransactions);
                chor.orderFulfillment();
                return null;
            default:
                throw new IllegalStateException("Unexpected session choreography: " + ctx.session.choreographyName());
        }
    }
}
