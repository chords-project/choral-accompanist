package dev.chords.choreographies;

import java.io.Serializable;
import java.util.List;

public class OrderItem@A implements Serializable@A {
    public final CartItem@A item;
    public final Money@A cost;

    public OrderItem(CartItem@A item, Money@A cost) {
        this.item = item;
        this.cost = cost;
    }

    public String@A toString() {
        return "[ OrderItem item="@A+item+" cost="@A+cost+" ]"@A;
    }
}