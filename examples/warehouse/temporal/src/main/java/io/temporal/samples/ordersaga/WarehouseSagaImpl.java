package io.temporal.samples.ordersaga;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.samples.ordersaga.web.ServerInfo;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class WarehouseSagaImpl implements WarehouseSaga {
    private static final Logger logger = LoggerFactory.getLogger(WarehouseSagaImpl.class);

    private WarehouseActivities warehouseActivities;
    private PaymentActivities paymentActivities;
    private LoyaltyActivities loyaltyActivities;

    private void configure() {
        ActivityOptions options = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(0)
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumInterval(Duration.ofSeconds(10)).setBackoffCoefficient(2)
                        .setDoNotRetry("warehouse.UserException").build())
                .build();
        this.warehouseActivities = Workflow.newActivityStub(
                WarehouseActivities.class,
                ActivityOptions.newBuilder(options).setTaskQueue(ServerInfo.getWarehouseTaskQueue()).build()
        );

        this.paymentActivities = Workflow.newActivityStub(
                PaymentActivities.class,
                ActivityOptions.newBuilder(options).setTaskQueue(ServerInfo.getPaymentTaskQueue()).build()
        );

        this.loyaltyActivities = Workflow.newActivityStub(
                LoyaltyActivities.class,
                ActivityOptions.newBuilder(options).setTaskQueue(ServerInfo.getLoyaltyTaskQueue()).build()
        );
    }

    @Override
    public String orderFulfillment(int sessionID) {
        configure();
        Saga saga = new Saga(new Saga.Options.Builder().build());

        try {
            var t1 = Workflow.currentTimeMillis();

            warehouseActivities.checkItemInStockAndReserveForOrder();
            saga.addCompensation(warehouseActivities::cancelOrderReservation);

            saga.addCompensation(paymentActivities::refundCustomer);
            paymentActivities.takeMoneyFromCustomer();

            saga.addCompensation(loyaltyActivities::compensatePointsFromCustomer);
            loyaltyActivities.awardPointsToCustomer();

            saga.addCompensation(() -> warehouseActivities.cancelDelivery(sessionID));
            warehouseActivities.packageAndSendOrder(sessionID);

            var t2 = Workflow.currentTimeMillis();
            return "order " + sessionID + " processed in " + (t2 - t1) + " ms";
        } catch (Exception e) {
            logger.error("Order processing failed, compensating.", e);
            saga.compensate();
            throw Workflow.wrap(e); // Wraps the exception to make it serializable by Temporal
        }
    }
}
