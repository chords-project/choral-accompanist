package dev.chords.choreographies;

import java.io.Serializable;

public class ReqPlaceOrder@A implements Serializable@A {
    public String@A user_id;
    public String@A user_currency;
    public Address@A address;
    public String@A email;
    public CreditCardInfo@A credit_card;

    public ReqPlaceOrder() {
        user_id = null@A;
        user_currency = null@A;
        address = new Address@A();
        email = null@A;
        credit_card = new CreditCardInfo@A();
    }

    public ReqPlaceOrder(
        String@A user_id,
        String@A user_currency,
        Address@A address,
        String@A email,
        CreditCardInfo@A credit_card
    ) {
        this.user_id = user_id;
        this.user_currency = user_currency;
        this.address = address;
        this.email = email;
        this.credit_card = credit_card;
    }

    public String@A toString() {
        return "[ ReqPlaceOrder user_id="@A+user_id+" currency="@A+user_currency+" address="@A+address+" email="@A+email+" credit_card="@A+credit_card+" ]"@A;
    }
}