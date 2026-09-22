package io.temporal.samples.ordersaga;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.samples.ordersaga.web.ServerInfo;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.client.WorkflowFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryWorkflowTest {
    public static class Activities implements WarehouseActivities, PaymentActivities, LoyaltyActivities {
        public void checkItemInStockAndReserveForOrder() {}
        public void cancelOrderReservation() {}
        public void packageAndSendOrder(int id) {}
        public void cancelDelivery(int id) {}
        public void takeMoneyFromCustomer() {}
        public void refundCustomer() {}
        public void awardPointsToCustomer() {}
        public void compensatePointsFromCustomer() {}
    }

    @Test @Timeout(30)
    void stockOutFailsWithoutCompensatingUnreservedStock() {
        var reservations = new AtomicInteger();
        var compensations = new AtomicInteger();
        var activities = new Activities() {
            @Override public void checkItemInStockAndReserveForOrder() {
                reservations.incrementAndGet();
                throw ApplicationFailure.newFailure("item out of stock", "warehouse.UserException");
            }
            @Override public void cancelOrderReservation() { compensations.incrementAndGet(); }
        };
        try (var env = TestWorkflowEnvironment.newInstance()) {
            var warehouse = env.newWorker(ServerInfo.getWarehouseTaskQueue());
            warehouse.registerWorkflowImplementationTypes(WarehouseSagaImpl.class);
            warehouse.registerActivitiesImplementations(activities);
            env.start();
            var workflow = env.getWorkflowClient().newWorkflowStub(WarehouseSaga.class,
                    WorkflowOptions.newBuilder().setTaskQueue(ServerInfo.getWarehouseTaskQueue()).build());
            assertThrows(WorkflowFailedException.class, () -> workflow.orderFulfillment(123));
            assertEquals(1, reservations.get());
            assertEquals(0, compensations.get());
        }
    }

    @Test @Timeout(100)
    void survivesSixtySecondsWithoutPaymentWorker() {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            var warehouse = env.newWorker(ServerInfo.getWarehouseTaskQueue());
            warehouse.registerWorkflowImplementationTypes(WarehouseSagaImpl.class);
            warehouse.registerActivitiesImplementations(new Activities());
            env.newWorker(ServerInfo.getLoyaltyTaskQueue()).registerActivitiesImplementations(new Activities());
            env.start();
            var workflow = env.getWorkflowClient().newWorkflowStub(WarehouseSaga.class,
                    WorkflowOptions.newBuilder().setTaskQueue(ServerInfo.getWarehouseTaskQueue()).build());
            var execution = WorkflowClient.start(workflow::orderFulfillment, 123);
            long started = env.currentTimeMillis();
            env.sleep(Duration.ofSeconds(60));
            // A separate factory lets payment begin polling only after the outage.
            var payment = WorkerFactory.newInstance(env.getWorkflowClient());
            try {
                payment.newWorker(ServerInfo.getPaymentTaskQueue()).registerActivitiesImplementations(new Activities());
                payment.start();
                var result = env.getWorkflowClient().newUntypedWorkflowStub(execution.getWorkflowId()).getResult(String.class);
                assertTrue(result.startsWith("order 123 processed"));
                assertTrue(env.currentTimeMillis() - started >= 60_000);
            } finally {
                payment.shutdownNow();
            }
        }
    }
}
