package dev.chords.warehouse.loyalty;

import choral.faulttolerance.Transaction;

public class LoyaltyService implements dev.chords.warehouse.choreograhpy.LoyaltyService {
    @Override
    public Transaction awardPointsToCustomer() {
        throw new RuntimeException("Not implemented yet");
    }
}
