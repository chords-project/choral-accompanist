package dev.chords.choreographies;

import java.util.ArrayList;
import java.io.Serializable;

public class OrderResult@A implements Serializable@A {
    public final String@A order_id;
    public final String@A shipping_tracking_id;
    public final Money@A shipping_cost;
    public final Address@A shipping_address;
    public final ArrayList@A<OrderItem> items;

    public OrderResult(
        String@A order_id,
        String@A shipping_tracking_id,
        Money@A shipping_cost,
        Address@A shipping_address,
        OrderItems@A items
    ) {
        this.order_id = order_id;
        this.shipping_tracking_id = shipping_tracking_id;
        this.shipping_cost = shipping_cost;
        this.shipping_address = shipping_address;
        this.items = items.items;
    }

    public String@A toString() {
        return "[ OrderResult order_id="@A+order_id+" trackingID="@A+shipping_tracking_id+" shipping_cost="@A+shipping_cost+" address="@A+shipping_address+" items="@A+items+" ]"@A;
    }
}