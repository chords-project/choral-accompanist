package dev.chords.warehouse.loyalty;

import choral.faulttolerance.FaultDataStore;
import choral.faulttolerance.FaultSessionContext;
import choral.faulttolerance.FaultTolerantServer;
import choral.faulttolerance.SQLDataStore;
import com.rabbitmq.client.ConnectionFactory;
import com.zaxxer.hikari.HikariDataSource;
import dev.chords.warehouse.choreograhpy.WarehouseOrder_Loyalty;

public class Loyalty implements FaultTolerantServer.FaultSessionEvent {

    public static void main(String[] args) throws Exception {
        var payment = new Loyalty();
        payment.start();
    }

    protected final FaultTolerantServer server;
    protected final LoyaltyService loyaltyService;

    public final String SERVICE_NAME = "LOYALTY";
    public final String RMQ_ADDRESS = "localhost";

    public Loyalty() throws Exception {
        var connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(RMQ_ADDRESS);
        var connection = connectionFactory.newConnection();

        HikariDataSource db = new HikariDataSource();
        db.setJdbcUrl("jdbc:postgresql://localhost:5432/warehouse_loyalty");
        db.setUsername("postgres");
        db.setPassword("postgres");
        FaultDataStore dataStore = new SQLDataStore(db);

        server = new FaultTolerantServer(dataStore, connection, SERVICE_NAME, this);
        loyaltyService = new LoyaltyService();
    }

    public void start() throws Exception {
        server.listen(RMQ_ADDRESS);
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
