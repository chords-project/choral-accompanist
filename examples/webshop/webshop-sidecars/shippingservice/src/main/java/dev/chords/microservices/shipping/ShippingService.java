package dev.chords.microservices.shipping;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import choral.reactive.ChannelConfigurator;
import choral.reactive.tracing.JaegerConfiguration;
import dev.chords.choreographies.Address;
import dev.chords.choreographies.Cart;
import dev.chords.choreographies.CartItem;
import dev.chords.choreographies.Money;
import hipstershop.ShippingServiceGrpc;
import hipstershop.Demo;
import hipstershop.Demo.GetQuoteRequest;
import hipstershop.Demo.GetQuoteResponse;
import hipstershop.Demo.ShipOrderRequest;
import hipstershop.Demo.ShipOrderResponse;
import hipstershop.ShippingServiceGrpc.ShippingServiceFutureStub;
import io.grpc.ManagedChannel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;

public class ShippingService implements dev.chords.choreographies.ShippingService {

    protected ManagedChannel channel;
    protected ShippingServiceFutureStub connection;
    protected Tracer tracer;

    public ShippingService(InetSocketAddress address, OpenTelemetrySdk telemetry) {
        channel = ChannelConfigurator.makeChannel(address, telemetry);

        connection = ShippingServiceGrpc.newFutureStub(channel);
        this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);
    }

    @Override
    public Money getQuote(Address address, Cart cart) {
        System.out.println("[SHIPPING] Get quote");

        Span span = tracer.spanBuilder("ShippingService.getQuote").startSpan();

        try (Scope scope = span.makeCurrent()) {

            GetQuoteRequest.Builder request = GetQuoteRequest.newBuilder()
                    .setAddress(
                            Demo.Address.newBuilder()
                                    .setStreetAddress(address.street_address)
                                    .setCity(address.city)
                                    .setState(address.state)
                                    .setCountry(address.country)
                                    .setZipCode(address.zip_code));

            for (CartItem item : cart.items) {
                request.addItems(Demo.CartItem.newBuilder().setProductId(item.product_id).setQuantity(item.quantity));
            }

            GetQuoteResponse response = connection.getQuote(request.build()).get(10, TimeUnit.SECONDS);

            Demo.Money m = response.getCostUsd();

            return new Money(m.getCurrencyCode(), (int) m.getUnits(), m.getNanos());
        } catch (Exception e) {
            span.setAttribute("error", true);
            span.recordException(e);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }

    }

    @Override
    public String shipOrder(Address address, Cart cart) {
        System.out.println("[SHIPPING] Ship order");

        Span span = tracer.spanBuilder("ShippingService.shipOrder").startSpan();

        try (Scope scope = span.makeCurrent()) {

            ShipOrderRequest.Builder request = ShipOrderRequest.newBuilder()
                    .setAddress(
                            Demo.Address.newBuilder()
                                    .setStreetAddress(address.street_address)
                                    .setCity(address.city)
                                    .setState(address.state)
                                    .setCountry(address.country)
                                    .setZipCode(address.zip_code));

            for (CartItem item : cart.items) {
                request.addItems(Demo.CartItem.newBuilder().setProductId(item.product_id).setQuantity(item.quantity));
            }

            ShipOrderResponse response = connection.shipOrder(request.build()).get(10, TimeUnit.SECONDS);

            return response.getTrackingId();
        } catch (Exception e) {
            span.setAttribute("error", true);
            span.recordException(e);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

}
