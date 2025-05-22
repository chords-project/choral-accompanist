package dev.chords.warehouse.loyalty;

import choral.faulttolerance.FaultSessionContext;
import choral.faulttolerance.FaultTolerantServer;
import com.rabbitmq.client.Connection;
import dev.chords.warehouse.choreograhpy.WarehouseOrder_Loyalty;

public class Loyalty implements FaultTolerantServer.FaultSessionEvent {

    public static void main(String[] args) throws Exception {
        var payment = new Loyalty();
        payment.start();
    }

    private FaultTolerantServer server;
    private LoyaltyService loyaltyService;

    public Loyalty() throws Exception {
        Connection connection = null;
        server = new FaultTolerantServer(connection, "LOYALTY", this);
        loyaltyService = new LoyaltyService();
    }

    public void start() throws Exception {
        server.listen("localhost");
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
