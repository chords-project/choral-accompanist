package dev.chords.choreographies;

import java.util.List;

public interface PaymentService@A {
    // RPCs
    String@A charge(Money@A price, CreditCardInfo@A creditCardInfo);
}