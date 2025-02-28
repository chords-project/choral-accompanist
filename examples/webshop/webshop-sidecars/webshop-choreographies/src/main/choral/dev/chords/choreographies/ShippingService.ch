package dev.chords.choreographies;

import java.util.List;

public interface ShippingService@A {
    // RPCs
    Money@A getQuote(Address@A address, Cart@A cart);
    String@A shipOrder(Address@A address, Cart@A cart);
}