package dev.chords.microservices.frontend;

import choral.accompanist.ReactiveSymChannel;
import choral.accompanist.SessionContext;
import choral.accompanist.connection.ClientConnectionManager;
import choral.accompanist.ReactiveClient;
import choral.accompanist.ReactiveServer;
import choral.accompanist.tracing.JaegerConfiguration;
import choral.accompanist.tracing.Logger;
import choral.accompanist.tracing.TelemetrySession;
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
            cartConn = ClientConnectionManager.defaultFactory()
                    .makeConnectionManager(ServiceResources.shared.cart, telemetry);
            currencyConn = ClientConnectionManager.defaultFactory()
                    .makeConnectionManager(ServiceResources.shared.currency, telemetry);
            shippingConn = ClientConnectionManager.defaultFactory()
                    .makeConnectionManager(ServiceResources.shared.shipping, telemetry);
            paymentConn = ClientConnectionManager.defaultFactory()
                    .makeConnectionManager(ServiceResources.shared.payment, telemetry);
            emailConn = ClientConnectionManager.defaultFactory()
                    .makeConnectionManager(ServiceResources.shared.email, telemetry);
        } catch (Exception e) {
            logger.exception("failed to start sidecar connections", e);
            throw new RuntimeException(e);
        }

        server = new ReactiveServer(Service.FRONTEND.name(), this.telemetry, ctx -> {
            logger.info(
                    "Received new session from " + ctx.session.senderName()
                            + " service: " + ctx.session);
            return null;
        });

        Thread.ofVirtual()
                .name("FRONTEND_CHORAL_SERVERS")
                .start(() -> {
                    try {
                        server.listen(ServiceResources.shared.frontend);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        logger.info("Done configuring frontend controller");
    }

    public Object handleSession(SessionContext ctx) throws Exception {
        WebshopSession session = new WebshopSession(ctx.session);
        switch (session.choreography) {
            case PLACE_ORDER:
                ctx.log("[PAYMENT] New PLACE_ORDER request");


                var currencyChan = new ReactiveSymChannel<>(
                        ctx.chanA(currencyConn),
                        ctx.chanB(Service.CURRENCY.name()));

                var shippingChan = new ReactiveSymChannel<>(
                        ctx.chanA(shippingConn),
                        ctx.chanB(Service.SHIPPING.name()));

                var paymentChan = new ReactiveSymChannel<>(
                        ctx.chanA(paymentConn),
                        ctx.chanB(Service.PAYMENT.name()));

                var emailChan = new ReactiveSymChannel<>(
                        ctx.chanA(emailConn),
                        ctx.chanB(Service.EMAIL.name()));

                ChorPlaceOrder_Client placeOrderChor = new ChorPlaceOrder_Client(
                        new ClientService(ctx.tracer()),
                        currencyChan,
                        shippingChan,
                        paymentChan,
                        emailChan,
                        ctx.chanA(cartConn)
                );

                // TODO: Allow payload to be passed with invokeManualSession()
                OrderResult result = placeOrderChor.placeOrder(request);

                ctx.log("[PAYMENT] PLACE_ORDER choreography completed");

                return result;
            default:
                throw new IllegalStateException("Unexpected session choreography: " + ctx.session.choreographyName());
        }
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


        try (Scope scope = span.makeCurrent();) {
            telemetrySession.log("Initiating PLACE_ORDER choreography");
            OrderResult result = (OrderResult) server.invokeManualSession(telemetrySession);

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
            span.end();
        }

    }
}
