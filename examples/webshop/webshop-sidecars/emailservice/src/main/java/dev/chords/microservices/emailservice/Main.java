package dev.chords.microservices.emailservice;

import choral.reactive.ReactiveServer;
import choral.reactive.SessionContext;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.JaegerConfiguration;
import dev.chords.choreographies.ChorPlaceOrder_Email;
import dev.chords.choreographies.ServiceResources;
import dev.chords.choreographies.Tracing;
import dev.chords.choreographies.WebshopSession;
import dev.chords.choreographies.WebshopSession.Service;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.net.InetSocketAddress;

public class Main {

    private static EmailService emailService;

    private static ClientConnectionManager frontendConn;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting choral email service");

        OpenTelemetrySdk telemetry = Tracing.initTracing("EmailService");

        int rpcPort = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        emailService = new EmailService(new InetSocketAddress("localhost", rpcPort), telemetry);

        frontendConn = ClientConnectionManager.defaultFactory()
                .makeConnectionManager(ServiceResources.shared.frontend, telemetry);

        ReactiveServer server = new ReactiveServer(Service.EMAIL.name(), telemetry,
                Main::handleNewSession);

        server.listen(ServiceResources.shared.email);
    }

    private static Object handleNewSession(SessionContext ctx)
            throws Exception {
        WebshopSession session = new WebshopSession(ctx.session);

        switch (session.choreography) {
            case PLACE_ORDER:
                ctx.log("[EMAIL] New PLACE_ORDER request");

                ChorPlaceOrder_Email placeOrderChor = new ChorPlaceOrder_Email(
                        emailService,
                        ctx.symChan(WebshopSession.Service.FRONTEND.name(), frontendConn)
                );

                placeOrderChor.placeOrder();

                ctx.log("[EMAIL] PLACE_ORDER choreography completed");
                return "PLACE_ORDER choreography completed";
            default:
                throw new IllegalStateException("Unexpected session choreography: " + ctx.session.choreographyName());
        }
    }
}
