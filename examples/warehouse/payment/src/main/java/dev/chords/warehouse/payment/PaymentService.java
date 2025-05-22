package dev.chords.warehouse.payment;

import choral.faulttolerance.Transaction;

public class PaymentService implements dev.chords.warehouse.choreograhpy.PaymentService {
    @Override
    public Transaction takeMoneyFromCustomer() {
        throw new RuntimeException("Not implemented");
    }
}
