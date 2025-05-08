package dev.chords.microservices.payment;

import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.ReactiveServer;
import choral.reactive.SessionContext;
import choral.reactive.tracing.JaegerConfiguration;
import dev.chords.choreographies.ChorPlaceOrder_Payment;
import dev.chords.choreographies.ServiceResources;
import dev.chords.choreographies.Tracing;
import dev.chords.choreographies.WebshopSession;
import dev.chords.choreographies.WebshopSession.Service;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.net.InetSocketAddress;

public class Main {

    public static PaymentService paymentService;

    public static OpenTelemetrySdk telemetry;

    public static ClientConnectionManager frontendConn;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting choral payment service");

        OpenTelemetrySdk telemetry = Tracing.initTracing("PaymentService");

        int rpcPort = Integer.parseInt(System.getenv().getOrDefault("PORT", "50051"));
        paymentService = new PaymentService(new InetSocketAddress("localhost", rpcPort), telemetry);

        frontendConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.frontend, telemetry);

        ReactiveServer server = new ReactiveServer(Service.PAYMENT.name(), telemetry,
                Main::handleNewSession);

        server.listen(ServiceResources.shared.payment);
    }

    private static void handleNewSession(SessionContext ctx) throws Exception {
        WebshopSession session = new WebshopSession(ctx.session);
        switch (session.choreography) {
            case PLACE_ORDER:
                ctx.log("[PAYMENT] New PLACE_ORDER request");

                ChorPlaceOrder_Payment placeOrderChor = new ChorPlaceOrder_Payment(
                        paymentService,
                        ctx.symChan(WebshopSession.Service.FRONTEND.name(), frontendConn));

                placeOrderChor.placeOrder();
                ctx.log("[PAYMENT] PLACE_ORDER choreography completed");

                break;
            default:
                ctx.log("[PAYMENT] Invalid choreography " + ctx.session.choreographyName());
                break;
        }
    }
}
