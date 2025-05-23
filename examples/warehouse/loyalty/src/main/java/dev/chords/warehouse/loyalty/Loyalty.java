package dev.chords.warehouse.loyalty;

import choral.faulttolerance.FaultSessionContext;
import choral.faulttolerance.FaultTolerantServer;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
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

        server = new FaultTolerantServer(connection, SERVICE_NAME, this);
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
