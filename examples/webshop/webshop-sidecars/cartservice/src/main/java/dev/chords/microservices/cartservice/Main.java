package dev.chords.microservices.cartservice;

import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.ReactiveServer;
import choral.reactive.SessionContext;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import dev.chords.choreographies.ChorPlaceOrder_Cart;
import dev.chords.choreographies.ServiceResources;
import dev.chords.choreographies.Tracing;
import dev.chords.choreographies.WebshopSession;
import dev.chords.choreographies.WebshopSession.Service;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    private static CartService cartService;

    private static ClientConnectionManager shippingConn;
    private static ClientConnectionManager productCatalogConn;
    private static Logger logger;

    public static void main(String[] args) throws Exception {
        OpenTelemetrySdk telemetry = Tracing.initTracing("CartService");
        logger = new Logger(telemetry, Main.class.getName());

        logger.info("Starting choral cart service");

        int rpcPort = Integer.parseInt(System.getenv().getOrDefault("ASPNETCORE_HTTP_PORTS", "7070"));
        cartService = new CartService(new InetSocketAddress("localhost", rpcPort), telemetry);

        shippingConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.shipping, telemetry);

        productCatalogConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.productCatalog,
                telemetry);

        ReactiveServer server = new ReactiveServer(Service.CART.name(), telemetry,
                Main::handleNewSession);

        server.listen(ServiceResources.shared.cart);
    }

    private static void handleNewSession(SessionContext ctx)
            throws Exception {
        WebshopSession session = new WebshopSession(ctx.session);

        switch (session.choreography) {
            case PLACE_ORDER:
                ctx.log("New PLACE_ORDER request");

                ChorPlaceOrder_Cart placeOrderChor = new ChorPlaceOrder_Cart(
                        cartService,
                        ctx.chanB(WebshopSession.Service.FRONTEND.name()),
                        ctx.chanA(productCatalogConn),
                        ctx.chanA(shippingConn));

                placeOrderChor.placeOrder();

                ctx.log("PLACE_ORDER choreography completed");

                break;
            default:
                ctx.log("Invalid choreography " + ctx.session.choreographyName());
                break;
        }
    }
}
