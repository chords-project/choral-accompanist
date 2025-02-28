package dev.chords.choreographies;

import java.io.Serializable;
import java.util.List;

public class Cart@A implements Serializable@A {
    public final String@A userID;
    public final List@A<CartItem> items;

    public Cart(String@A userID, List@A<CartItem> items) {
        this.userID = userID;
        this.items = items;
    }
}