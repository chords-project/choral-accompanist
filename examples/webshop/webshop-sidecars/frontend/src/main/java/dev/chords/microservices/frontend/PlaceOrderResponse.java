package dev.chords.microservices.frontend;

import dev.chords.choreographies.OrderResult;

public class PlaceOrderResponse {
    public OrderResult order;

    public PlaceOrderResponse(OrderResult order) {
        this.order = order;
    }
}
