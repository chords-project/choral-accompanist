package dev.chords.microservices.productcatalog;

import choral.accompanist.connection.ClientConnectionManager;
import choral.accompanist.ReactiveServer;
import choral.accompanist.tracing.JaegerConfiguration;
import dev.chords.choreographies.ChorPlaceOrder_ProductCatalog;
import dev.chords.choreographies.ServiceResources;
import dev.chords.choreographies.Tracing;
import dev.chords.choreographies.WebshopSession;
import dev.chords.choreographies.WebshopSession.Service;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.net.InetSocketAddress;

public class Main {

    public static OpenTelemetrySdk telemetry;

    public static ClientConnectionManager currencyConn;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting choral product catalog service");

        OpenTelemetrySdk telemetry = Tracing.initTracing("ProductCatalogService");

        int rpcPort = Integer.parseInt(System.getenv().getOrDefault("PORT", "3550"));
        ProductCatalogService catalogService = new ProductCatalogService(new InetSocketAddress("localhost", rpcPort),
                telemetry);

        currencyConn = ClientConnectionManager.defaultFactory()
                .makeConnectionManager(ServiceResources.shared.currency, telemetry);

        ReactiveServer server = new ReactiveServer(Service.PRODUCT_CATALOG.name(), telemetry,
                ctx -> {
                    WebshopSession session = new WebshopSession(ctx.session);
                    switch (session.choreography) {
                        case PLACE_ORDER:
                            ctx.log("[PRODUCT_CATALOG] New PLACE_ORDER request");

                            ChorPlaceOrder_ProductCatalog placeOrderChor = new ChorPlaceOrder_ProductCatalog(
                                    catalogService,
                                    ctx.chanB(WebshopSession.Service.CART.name()),
                                    ctx.chanA(currencyConn));

                            placeOrderChor.placeOrder();
                            ctx.log("[PRODUCT_CATALOG] PLACE_ORDER choreography completed");

                            return "PLACE_ORDER choreography completed";
                        default:
                            throw new IllegalStateException("Unexpected session choreography: " + ctx.session.choreographyName());
                    }
                });

        server.listen(ServiceResources.shared.productCatalog);
    }
}
