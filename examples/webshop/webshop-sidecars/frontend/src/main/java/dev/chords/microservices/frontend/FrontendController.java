package dev.chords.microservices.frontend;

import choral.reactive.ReactiveSymChannel;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.ReactiveClient;
import choral.reactive.ReactiveServer;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import choral.reactive.tracing.TelemetrySession;
import dev.chords.choreographies.*;
import dev.chords.choreographies.WebshopSession.Choreography;
import dev.chords.choreographies.WebshopSession.Service;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;

import java.io.IOException;
import java.net.URISyntaxException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FrontendController {

    ReactiveServer server;
    OpenTelemetry telemetry;
    Logger logger;
    DoubleHistogram checkoutDurationHistogram;

    ClientConnectionManager cartConn;
    ClientConnectionManager currencyConn;
    ClientConnectionManager shippingConn;
    ClientConnectionManager paymentConn;
    ClientConnectionManager emailConn;

    public FrontendController() {
        this.telemetry = Tracing.initTracing("Frontend");
        this.logger = new Logger(telemetry, FrontendController.class.getName());

        this.checkoutDurationHistogram = telemetry.getMeter(JaegerConfiguration.TRACER_NAME)
            .histogramBuilder("choral.frontend.checkout-duration")
            .setUnit("ms")
            .setDescription("Time it takes to perform a checkout")
            .build();

        try {
            cartConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.cart,
                    telemetry);
            currencyConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.currency,
                    telemetry);
            shippingConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.shipping,
                    telemetry);
            paymentConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.payment,
                    telemetry);
            emailConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.email,
                telemetry);
        } catch (URISyntaxException | IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        server = new ReactiveServer(Service.FRONTEND.name(), this.telemetry, ctx -> {
            logger.info(
                    "Received new session from " + ctx.session.senderName()
                            + " service: " + ctx.session);
        });

        Thread.ofVirtual()
                .name("FRONTEND_CHORAL_SERVERS")
                .start(() -> {
                    try {
                        server.listen(ServiceResources.shared.frontend);
                    } catch (URISyntaxException | IOException e) {
                        throw new RuntimeException(e);
                    }
                });

        logger.info("Done configuring frontend controller");
    }

    @GetMapping("/ping")
    String ping() {
        return "pong";
    }

    @PostMapping("/checkout")
    PlaceOrderResponse checkout(@RequestBody ReqPlaceOrder request) {
        logger.info("Placing order: " + request);

        Long startTime = System.nanoTime();

        WebshopSession session = WebshopSession.makeSession(Choreography.PLACE_ORDER,
                Service.FRONTEND);

        Span span = telemetry
                .getTracer(JaegerConfiguration.TRACER_NAME)
                .spanBuilder("Frontend: Checkout request")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("choreography.session", session.toString())
                .startSpan();

        TelemetrySession telemetrySession = new TelemetrySession(telemetry, session,
                span);

        server.registerSession(session, telemetrySession);

        try (Scope scope = span.makeCurrent();
                ReactiveClient cartClient = new ReactiveClient(
                        cartConn, Service.FRONTEND.name(), telemetrySession);
                ReactiveClient currencyClient = new ReactiveClient(
                        currencyConn, Service.FRONTEND.name(), telemetrySession);
                ReactiveClient shippingClient = new ReactiveClient(
                        shippingConn, Service.FRONTEND.name(), telemetrySession);
                ReactiveClient paymentClient = new ReactiveClient(
                        paymentConn, Service.FRONTEND.name(), telemetrySession);
             ReactiveClient emailClient = new ReactiveClient(
                 emailConn, Service.FRONTEND.name(), telemetrySession);) {

            telemetrySession.log("Initiating PLACE_ORDER choreography");

            var currencyChan = new ReactiveSymChannel<>(currencyClient.chanA(session),
                    server.chanB(session, Service.CURRENCY.name()));

            var shippingChan = new ReactiveSymChannel<>(shippingClient.chanA(session),
                    server.chanB(session, Service.SHIPPING.name()));

            var paymentChan = new ReactiveSymChannel<>(paymentClient.chanA(session),
                    server.chanB(session, Service.PAYMENT.name()));

            var emailChan = new ReactiveSymChannel<>(emailClient.chanA(session),
                    server.chanB(session, Service.EMAIL.name()));

            ChorPlaceOrder_Client placeOrderChor = new ChorPlaceOrder_Client(
                    new ClientService(telemetrySession.tracer),
                    currencyChan,
                    shippingChan,
                    paymentChan,
                    emailChan,
                    cartClient.chanA(session)
                );

            OrderResult result = placeOrderChor.placeOrder(request);

            telemetrySession.log("Finished PLACE_ORDER choreography",
                    Attributes.builder().put("order.result", result.toString()).build());

            Long endTime = System.nanoTime();
            checkoutDurationHistogram.record((endTime - startTime) / 1_000_000.,
                Attributes.builder()
                    .put("success", true)
                    .build()
                );

            return new PlaceOrderResponse(result);
        } catch (Exception e) {
            telemetrySession.recordException("Frontend PLACE_ORDER choreography failed",
                    e, true);

            Long endTime = System.nanoTime();
            checkoutDurationHistogram.record((endTime - startTime) / 1_000_000.,
                Attributes.builder()
                    .put("success", false)
                    .build()
            );

            throw new RuntimeException(e);
        } finally {
            if (span != null)
                span.end();
        }
    }
}
