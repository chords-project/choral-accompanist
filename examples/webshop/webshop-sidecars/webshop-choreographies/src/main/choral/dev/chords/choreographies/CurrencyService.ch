package dev.chords.choreographies;

import java.util.List;

public interface CurrencyService@A {
    // RPCs
    List@A<String> supportedCurrencies();
    Money@A convert(Money@A from, String@A toCurrency);
    
    // Helpers
    OrderItems@A convertProducts(OrderItems@A products, String@A toCurrency);
}