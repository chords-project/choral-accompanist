package io.temporal.samples.ordersaga;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface WarehouseActivities {
    @ActivityMethod
    void checkItemInStockAndReserveForOrder();

    @ActivityMethod
    void cancelOrderReservation();

    @ActivityMethod
    void packageAndSendOrder();

    @ActivityMethod
    void cancelDelivery();
}
