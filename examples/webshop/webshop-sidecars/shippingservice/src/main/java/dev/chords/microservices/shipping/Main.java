package dev.chords.microservices.shipping;

import choral.accompanist.connection.ClientConnectionManager;
import choral.accompanist.ReactiveServer;
import choral.accompanist.SessionContext;
import choral.accompanist.tracing.JaegerConfiguration;
import dev.chords.choreographies.ChorPlaceOrder_Shipping;
import dev.chords.choreographies.ServiceResources;
import dev.chords.choreographies.Tracing;
import dev.chords.choreographies.WebshopSession;
import dev.chords.choreographies.WebshopSession.Service;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.net.InetSocketAddress;

public class Main {

    public static ShippingService shippingService;

    public static OpenTelemetrySdk telemetry;

    public static ClientConnectionManager frontendConn;
    public static ClientConnectionManager currencyConn;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting choral shipping service");

        OpenTelemetrySdk telemetry = Tracing.initTracing("ShippingService");

        int rpcPort = Integer.parseInt(System.getenv().getOrDefault("PORT", "50051"));
        shippingService = new ShippingService(new InetSocketAddress("localhost", rpcPort), telemetry);

        frontendConn = ClientConnectionManager.defaultFactory()
                .makeConnectionManager(ServiceResources.shared.frontend, telemetry);

        currencyConn = ClientConnectionManager.defaultFactory()
                .makeConnectionManager(ServiceResources.shared.currency, telemetry);

        ReactiveServer server = new ReactiveServer(Service.SHIPPING.name(), telemetry,
                Main::handleNewSession);

        server.listen(ServiceResources.shared.shipping);
    }

    private static Object handleNewSession(SessionContext ctx) throws Exception {
        WebshopSession session = new WebshopSession(ctx.session);
        switch (session.choreography) {
            case PLACE_ORDER:
                ctx.log("[SHIPPING] New PLACE_ORDER request");

                ChorPlaceOrder_Shipping placeOrderChor = new ChorPlaceOrder_Shipping(
                        shippingService,
                        ctx.symChan(WebshopSession.Service.FRONTEND.name(), frontendConn),
                        ctx.chanB(WebshopSession.Service.CART.name()),
                        ctx.chanA(currencyConn));

                placeOrderChor.placeOrder();
                ctx.log("[SHIPPING] PLACE_ORDER choreography completed");

                return "PLACE_ORDER choreography completed";
            default:
                throw new IllegalStateException("Unexpected session choreography: " + ctx.session.choreographyName());
        }
    }
}
