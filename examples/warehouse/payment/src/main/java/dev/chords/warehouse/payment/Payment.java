package dev.chords.warehouse.payment;

import choral.faulttolerance.FaultDataStore;
import choral.faulttolerance.FaultSessionContext;
import choral.faulttolerance.FaultTolerantServer;
import choral.faulttolerance.SQLDataStore;
import com.rabbitmq.client.ConnectionFactory;
import com.zaxxer.hikari.HikariDataSource;
import dev.chords.warehouse.choreograhpy.WarehouseOrder_Payment;

public class Payment implements FaultTolerantServer.FaultSessionEvent {

    public static void main(String[] args) throws Exception {
        var payment = new Payment();
        payment.start();
    }

    private final FaultTolerantServer server;
    private final PaymentService paymentService;

    public static final String SERVICE_NAME = "PAYMENT";
    public static final String RMQ_ADDRESS = "localhost";

    public Payment() throws Exception {
        var connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(RMQ_ADDRESS);
        var connection = connectionFactory.newConnection();

        HikariDataSource db = new HikariDataSource();
        db.setJdbcUrl("jdbc:postgresql://localhost:5432/warehouse_payment");
        db.setUsername("postgres");
        db.setPassword("postgres");
        FaultDataStore dataStore = new SQLDataStore(db);

        server = new FaultTolerantServer(dataStore, connection, SERVICE_NAME, this);
        paymentService = new PaymentService();
    }

    public void start() throws Exception {
        server.listen(RMQ_ADDRESS);
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
