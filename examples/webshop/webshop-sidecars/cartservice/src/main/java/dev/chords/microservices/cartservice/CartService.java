package dev.chords.microservices.cartservice;

import java.util.List;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import choral.reactive.ChannelConfigurator;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import dev.chords.choreographies.Cart;
import dev.chords.choreographies.CartItem;
import hipstershop.CartServiceGrpc;
import hipstershop.Demo;
import hipstershop.CartServiceGrpc.CartServiceFutureStub;
import hipstershop.Demo.EmptyCartRequest;
import io.grpc.ManagedChannel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;

public class CartService implements dev.chords.choreographies.CartService, AutoCloseable {

    protected ManagedChannel channel;
    protected CartServiceFutureStub connection;
    protected Tracer tracer;
    protected Logger logger;

    public CartService(InetSocketAddress address, OpenTelemetrySdk telemetry) {
        channel = ChannelConfigurator.makeChannel(address, telemetry);

        this.connection = CartServiceGrpc.newFutureStub(channel);
        this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);
        this.logger = new Logger(telemetry, CartService.class.getName());
    }

    public void addItem(String userID, String productID, int quantity) {
        Span span = span = tracer.spanBuilder("CartService.addItem")
            .setAttribute("request.userID", userID)
            .setAttribute("request.productID", productID)
            .setAttribute("request.quantity", quantity)
            .startSpan();

        try (Scope scope = span.makeCurrent();) {
            logger.info("Adding item to cart");

            Demo.AddItemRequest request = Demo.AddItemRequest.newBuilder()
                .setUserId(userID)
                .setItem(
                    Demo.CartItem.newBuilder()
                        .setProductId(productID)
                        .setQuantity(quantity))
                .build();

            connection.addItem(request);

        } finally {
            span.end();
        }
    }

    @Override
    public Cart getCart(String userID) {
        Span span = tracer.spanBuilder("CartService.getCart")
                .setAttribute("request.userID", userID).startSpan();

        try (Scope scope = span.makeCurrent();) {
            logger.info("Get cart for user: " + userID);

            Demo.GetCartRequest request = Demo.GetCartRequest.newBuilder()
                    .setUserId(userID)
                    .build();

            Demo.Cart cart = connection.getCart(request).get(10, TimeUnit.SECONDS);

            List<CartItem> items = cart.getItemsList().stream()
                    .map(item -> new CartItem(item.getProductId(), item.getQuantity())).toList();

            return new Cart(cart.getUserId(), items);
        } catch (Exception e) {
            span.setAttribute("error", true);
            span.recordException(e);

            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    public void emptyCart(String userID) {
        Span span = tracer.spanBuilder("CartService.emptyCart").setAttribute("request.userID", userID).startSpan();
        try (Scope scope = span.makeCurrent()) {
            logger.info("Empty cart for user: " + userID);

            EmptyCartRequest request = EmptyCartRequest.newBuilder().setUserId(userID).build();

            connection.emptyCart(request).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            span.setAttribute("error", true);
            span.recordException(e);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    @Override
    public void close() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

}
