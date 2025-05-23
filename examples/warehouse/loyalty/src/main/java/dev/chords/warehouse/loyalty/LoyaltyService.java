package dev.chords.warehouse.loyalty;

import choral.faulttolerance.SQLTransaction;
import choral.faulttolerance.Transaction;

public class LoyaltyService implements dev.chords.warehouse.choreograhpy.LoyaltyService {
    @Override
    public Transaction awardPointsToCustomer() {
        System.out.println("- Loyalty make transaction: awardPointsToCustomer");

        return new Transaction() {
            @Override
            public boolean commit(SQLTransaction trans) {
                System.out.println("- Loyalty commit transaction: awardPointsToCustomer");
                return true;
            }

            @Override
            public void compensate(SQLTransaction trans) {
                System.out.println("- Loyalty compensate transaction: awardPointsToCustomer");
            }
        };
    }
}
