package io.temporal.samples.ordersaga;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.samples.ordersaga.web.ServerInfo;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Random;

public class WarehouseSagaImpl implements WarehouseSaga {
    private static final Logger logger = LoggerFactory.getLogger(WarehouseSagaImpl.class);

    private final WarehouseActivities warehouseActivities;
    private final PaymentActivities paymentActivities;
    private final LoyaltyActivities loyaltyActivities;

    public WarehouseSagaImpl() {
        // because we want to trigger Saga compensation any failure
        ActivityOptions options = ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(
                        RetryOptions.newBuilder()
                                .setMaximumAttempts(1) // because we want to trigger Saga compensation any failure
                                .build())
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
    public void orderFulfillment() {
        Saga saga = new Saga(new Saga.Options.Builder().build());

        Random rand = new Random();
        int sessionID = rand.nextInt();

        try {
            saga.addCompensation(warehouseActivities::cancelOrderReservation);
            warehouseActivities.checkItemInStockAndReserveForOrder();

            saga.addCompensation(paymentActivities::refundCustomer);
            paymentActivities.takeMoneyFromCustomer();

            saga.addCompensation(loyaltyActivities::compensatePointsFromCustomer);
            loyaltyActivities.awardPointsToCustomer();

            saga.addCompensation(() -> warehouseActivities.cancelDelivery(sessionID));
            warehouseActivities.packageAndSendOrder(sessionID);
        } catch (Exception e) {
            logger.error("Order processing failed, compensating.", e);
            saga.compensate();
            throw Workflow.wrap(e); // Wraps the exception to make it serializable by Temporal
        }
    }
}
