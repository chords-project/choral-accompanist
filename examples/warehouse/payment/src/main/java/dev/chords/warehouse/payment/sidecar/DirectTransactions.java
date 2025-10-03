package dev.chords.warehouse.payment.sidecar;

import choral.faulttolerance.SQLTransaction;
import choral.faulttolerance.Transaction;

import java.util.Set;

public class DirectTransactions implements PaymentTransactions {

    public Set<Transaction> allTransactions() {
        return Set.of(takeMoneyFromCustomer());
    }

    @Override
    public Transaction takeMoneyFromCustomer() {
        System.out.println("- Payment make transaction: takeMoneyFromCustomer");

        return new Transaction() {
            @Override
            public String transactionName() {
                return "takeMoneyFromCustomer";
            }

            @Override
            public boolean commit(int sessionID, SQLTransaction trans) {
                System.out.println("- Payment commit transaction: takeMoneyFromCustomer");
                return true;
            }

            @Override
            public void compensate(int sessionID, SQLTransaction trans) {
                System.out.println("- Payment compensate transaction: takeMoneyFromCustomer");
            }
        };
    }
}
