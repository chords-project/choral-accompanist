package dev.chords.microservices.emailservice;

import choral.reactive.ChannelConfigurator;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import dev.chords.choreographies.OrderResult;
import hipstershop.Demo;
import hipstershop.EmailServiceGrpc;
import hipstershop.EmailServiceGrpc.EmailServiceFutureStub;
import io.grpc.ManagedChannel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class EmailService implements dev.chords.choreographies.EmailService, AutoCloseable {

    protected ManagedChannel channel;
    protected EmailServiceFutureStub connection;
    protected Tracer tracer;
    protected Logger logger;

    public EmailService(InetSocketAddress address, OpenTelemetrySdk telemetry) {
        channel = ChannelConfigurator.makeChannel(address, telemetry);

        this.connection = EmailServiceGrpc.newFutureStub(channel);
        this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);
        this.logger = new Logger(telemetry, EmailService.class.getName());
    }

    @Override
    public void sendOrderConfirmation(String email, OrderResult orderResult) {
        logger.info("Send order email confirmation");

        Span span = tracer.spanBuilder("EmailService.sendOrderConfirmation")
            .setAttribute("request.orderResult", orderResult.toString()).startSpan();
        try (Scope scope = span.makeCurrent()) {

            Demo.SendOrderConfirmationRequest request = Demo.SendOrderConfirmationRequest.newBuilder()
                    .setEmail(email)
                    .setOrder(
                        Demo.OrderResult.newBuilder()
                            .setOrderId(orderResult.order_id)
                            .setShippingTrackingId(orderResult.shipping_tracking_id)
                            .setShippingCost(
                                Demo.Money.newBuilder()
                                    .setCurrencyCode(orderResult.shipping_cost.currencyCode)
                                    .setUnits(orderResult.shipping_cost.units)
                                    .setNanos(orderResult.shipping_cost.nanos)
                            )
                            .setShippingAddress(
                                Demo.Address.newBuilder()
                                    .setStreetAddress(orderResult.shipping_address.street_address)
                                    .setCity(orderResult.shipping_address.city)
                                    .setState(orderResult.shipping_address.state)
                                    .setCountry(orderResult.shipping_address.country)
                                    .setZipCode(orderResult.shipping_address.zip_code)
                            )
                            .addAllItems(
                                orderResult.items.stream().map(item ->
                                    Demo.OrderItem.newBuilder()
                                        .setItem(
                                            Demo.CartItem.newBuilder()
                                                .setProductId(item.item.product_id)
                                                .setQuantity(item.item.quantity)
                                                .build()
                                        )
                                        .setCost(
                                            Demo.Money.newBuilder()
                                                .setCurrencyCode(item.cost.currencyCode)
                                                .setUnits(item.cost.units)
                                                .setNanos(item.cost.nanos)
                                        )
                                        .build()
                                ).toList()
                            )
                    )
                    .build();

            connection.sendOrderConfirmation(request).get(10, TimeUnit.SECONDS);
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
