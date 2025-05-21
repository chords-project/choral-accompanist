package dev.chords.warehouse.choreography;

import choral.faulttolerance.Transaction;

public interface PaymentService@A {
    Transaction@A takeMoneyFromCustomer();
}
