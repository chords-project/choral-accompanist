package choral.accompanist.examples.warehouse.payment.sidecar;

import choral.accompanist.faulttolerance.Transaction;

import java.util.Set;

public interface PaymentTransactions extends choral.accompanist.examples.warehouse.choreograhpy.PaymentService {
    Set<Transaction> allTransactions();
}
