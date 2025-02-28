package dev.chords.microservices.frontend;

import dev.chords.choreographies.Money;
import dev.chords.choreographies.OrderItems;
import dev.chords.microservices.frontend.MoneyUtils.MoneyException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

public class ClientService implements dev.chords.choreographies.ClientService {

    protected Tracer tracer;

    public ClientService(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Money totalPrice(OrderItems orderItems, Money shippingCost) {
        Span span = tracer.spanBuilder("ClientService.totalPrice").startSpan();

        try (Scope scope = span.makeCurrent()) {
            Money total = shippingCost;

            for (var item : orderItems.items) {
                Money itemsPrice = MoneyUtils.multiplySlow(item.cost, item.item.quantity);
                total = MoneyUtils.sum(total, itemsPrice);
            }

            return total;
        } catch (MoneyException e) {
            span.setAttribute("error", true);
            span.recordException(e);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }
}
