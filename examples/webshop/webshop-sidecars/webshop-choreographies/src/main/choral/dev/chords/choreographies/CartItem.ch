package dev.chords.choreographies;

import java.io.Serializable;

public class CartItem@A implements Serializable@A {
    public final String@A product_id;
    public final Integer@A quantity;

    public CartItem(String@A product_id, Integer@A quantity) {
        this.product_id = product_id;
        this.quantity = quantity;
    }
}