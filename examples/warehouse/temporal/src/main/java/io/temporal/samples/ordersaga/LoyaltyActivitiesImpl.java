package io.temporal.samples.ordersaga;

public class LoyaltyActivitiesImpl implements LoyaltyActivities {
    @Override
    public void awardPointsToCustomer() {
        System.out.println("awardPointsToCustomer");
    }

    @Override
    public void compensatePointsToCustomer() {
        System.out.println("compensatePointsToCustomer");
    }
}
