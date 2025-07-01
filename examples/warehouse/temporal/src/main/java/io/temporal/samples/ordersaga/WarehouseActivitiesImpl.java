package io.temporal.samples.ordersaga;

public class WarehouseActivitiesImpl implements WarehouseActivities {
    @Override
    public void checkItemInStockAndReserveForOrder() {
        System.out.println("checkItemInStockAndReserveForOrder");
    }

    @Override
    public void cancelOrderReservation() {
        System.out.println("cancelOrderReservation");
    }

    @Override
    public void packageAndSendOrder() {
        System.out.println("packageAndSendOrder");
    }

    @Override
    public void cancelDelivery() {
        System.out.println("cancelDelivery");
    }
}
