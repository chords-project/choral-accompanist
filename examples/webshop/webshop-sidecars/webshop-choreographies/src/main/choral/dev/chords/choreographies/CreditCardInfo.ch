package dev.chords.choreographies;

import java.io.Serializable;
import java.util.List;

public class CreditCardInfo@A implements Serializable@A {
    public String@A credit_card_number;
    public Integer@A credit_card_cvv;
    public Integer@A credit_card_expiration_year;
    public Integer@A credit_card_expiration_month;

    public CreditCardInfo() {
        credit_card_number = ""@A;
        credit_card_cvv = 0@A;
        credit_card_expiration_year = 0@A;
        credit_card_expiration_month = 0@A;
    }

    public CreditCardInfo(
        String@A credit_card_number,
        Integer@A credit_card_cvv,
        Integer@A credit_card_expiration_year,
        Integer@A credit_card_expiration_month
    ) {
        this.credit_card_number = credit_card_number;
        this.credit_card_cvv = credit_card_cvv;
        this.credit_card_expiration_year = credit_card_expiration_year;
        this.credit_card_expiration_month = credit_card_expiration_month;
    }

    public String@A toString() {
        return "[ CreditCardInfo number="@A+credit_card_number+" cvv="@A+credit_card_cvv+" year="@A+credit_card_expiration_year+" month="@A+credit_card_expiration_month+" ]"@A;
    }
}