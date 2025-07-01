package io.temporal.samples.ordersaga;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PaymentActivities {
    @ActivityMethod
    void takeMoneyFromCustomer();

    @ActivityMethod
    void refundCustomer();
}
