package dev.chords.microservices.frontend;

import dev.chords.choreographies.Money;

public class MoneyUtils {

    private static final int NANOS_MIN = -999999999;
    private static final int NANOS_MAX = +999999999;
    private static final int NANOS_MOD = 1000000000;

    public static abstract class MoneyException extends Exception {
        private MoneyException(String message) {
            super(message);
        }
    }

    public static class InvalidValueException extends MoneyException {
        public InvalidValueException() {
            super("one of the specified money values is invalid");
        }
    }

    public static class MismatchingCurrencyException extends MoneyException {
        public MismatchingCurrencyException(String msg) {
            super("one of the specified money values is invalid: " + msg);
        }
    }

    private static boolean signMatches(Money m) {
        return m.nanos == 0 || m.units == 0 || (m.nanos < 0) == (m.units < 0);
    }

    private static boolean validNanos(int nanos) {
        return NANOS_MIN <= nanos && nanos <= NANOS_MAX;
    }

    public static boolean areSameCurrency(Money l, Money r) {
        return l.currencyCode.equals(r.currencyCode) && !l.currencyCode.isEmpty();
    }

    public static boolean isValid(Money m) {
        return signMatches(m) && validNanos(m.nanos);
    }

    public static Money sum(Money l, Money r) throws MoneyException {
        if (!isValid(l) || !isValid(r)) {
            throw new InvalidValueException();
        } else if (!areSameCurrency(l, r)) {
            throw new MismatchingCurrencyException(l.currencyCode + " not equal to " + r.currencyCode);
        }

        int units = l.units + r.units;
        int nanos = l.nanos + r.nanos;

        if ((units == 0 && nanos == 0) || (units > 0 && nanos >= 0) || (units < 0 && nanos <= 0)) {
            // same sign <units, nanos>
            units += nanos / NANOS_MOD;
            nanos = nanos % NANOS_MOD;
        } else {
            // different sign. nanos guaranteed to not go over the limit
            if (units > 0) {
                units--;
                nanos += NANOS_MOD;
            } else {
                units++;
                nanos -= NANOS_MOD;
            }
        }

        return new Money(l.currencyCode, units, nanos);
    }

    public static Money multiplySlow(Money m, int n) throws MoneyException {
        Money out = m;
        while (n > 1) {
            out = sum(out, m);
            n--;
        }
        return out;
    }

}
