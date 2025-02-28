package dev.chords.choreographies;

import java.io.Serializable;
import java.util.ArrayList;

public class OrderItems@A implements Serializable@A {
    public final ArrayList@A<OrderItem> items;

    public OrderItems(ArrayList@A<OrderItem> items) {
        this.items = items;
    }

    public String@A toString() {
        return "[ OrderItems items="@A+items+" ]"@A;
    }
}