package dev.chords.warehouse.payment;

import choral.faulttolerance.SQLTransaction;
import choral.faulttolerance.Transaction;

import java.sql.SQLException;

public class PaymentService implements dev.chords.warehouse.choreograhpy.PaymentService {
    @Override
    public Transaction takeMoneyFromCustomer() {
        System.out.println("- Payment make transaction: takeMoneyFromCustomer");

        return new Transaction() {
            @Override
            public boolean commit(SQLTransaction trans) {
                System.out.println("- Payment commit transaction: takeMoneyFromCustomer");
                return true;
            }

            @Override
            public void compensate(SQLTransaction trans) {
                System.out.println("- Payment compensate transaction: takeMoneyFromCustomer");
            }
        };
    }
}
