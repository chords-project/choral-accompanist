package io.temporal.samples.ordersaga;

public class PaymentActivitiesImpl implements PaymentActivities {
    @Override
    public void takeMoneyFromCustomer() {
        System.out.println("takeMoneyFromCustomer");
    }

    @Override
    public void refundCustomer() {
        System.out.println("refundCustomer");
    }
}
