package dev.chords.warehouse.payment.sidecar;

import choral.faulttolerance.Transaction;

import java.util.Set;

public interface PaymentTransactions extends dev.chords.warehouse.choreograhpy.PaymentService {
    Set<Transaction> allTransactions();
}
