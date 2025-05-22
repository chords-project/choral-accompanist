package dev.chords.warehouse.payment;

import choral.faulttolerance.FaultSessionContext;
import choral.faulttolerance.FaultTolerantServer;
import com.rabbitmq.client.Connection;
import dev.chords.warehouse.choreograhpy.WarehouseOrder_Payment;

public class Payment implements FaultTolerantServer.FaultSessionEvent {

    public static void main(String[] args) throws Exception {
        var payment = new Payment();
        payment.start();
    }

    private FaultTolerantServer server;
    private PaymentService paymentService;

    public Payment() throws Exception {
        Connection connection = null;
        server = new FaultTolerantServer(connection, "PAYMENT", this);
        paymentService = new PaymentService();
    }

    public void start() throws Exception {
        server.listen("localhost");
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
