package dev.chords.warehouse.payment;

import choral.faulttolerance.SQLTransaction;
import choral.faulttolerance.Transaction;
import choral.reactive.Session;

public class PaymentService implements dev.chords.warehouse.choreograhpy.PaymentService {
    @Override
    public Transaction takeMoneyFromCustomer() {
        System.out.println("- Payment make transaction: takeMoneyFromCustomer");

        return new Transaction() {
            @Override
            public String transactionName() {
                return "takeMoneyFromCustomer";
            }

            @Override
            public boolean commit(Session session, SQLTransaction trans) {
                System.out.println("- Payment commit transaction: takeMoneyFromCustomer");
                return true;
            }

            @Override
            public void compensate(Session session, SQLTransaction trans) {
                System.out.println("- Payment compensate transaction: takeMoneyFromCustomer");
            }
        };
    }
}
