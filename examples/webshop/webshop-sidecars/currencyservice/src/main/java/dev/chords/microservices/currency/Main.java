package dev.chords.microservices.currency;

import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.ReactiveServer;
import choral.reactive.SessionContext;
import choral.reactive.tracing.JaegerConfiguration;
import dev.chords.choreographies.ChorPlaceOrder_Currency;
import dev.chords.choreographies.ServiceResources;
import dev.chords.choreographies.Tracing;
import dev.chords.choreographies.WebshopSession;
import dev.chords.choreographies.WebshopSession.Service;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.net.InetSocketAddress;

public class Main {

    private static CurrencyService currencyService;
    private static OpenTelemetrySdk telemetry;

    private static ClientConnectionManager frontendConn;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting choral currency service");

        OpenTelemetrySdk telemetry = Tracing.initTracing("CurrencyService");

        int rpcPort = Integer.parseInt(System.getenv().getOrDefault("PORT", "7000"));
        currencyService = new CurrencyService(new InetSocketAddress("localhost", rpcPort), telemetry);

        frontendConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.frontend, telemetry);

        ReactiveServer server = new ReactiveServer(Service.CURRENCY.name(), telemetry,
                Main::handleNewSession);

        server.listen(ServiceResources.shared.currency);
    }

    private static void handleNewSession(SessionContext ctx) throws Exception {
        WebshopSession session = new WebshopSession(ctx.session);
        switch (session.choreography) {
            case PLACE_ORDER:
                ctx.log("[CURRENCY] New PLACE_ORDER request");

                ChorPlaceOrder_Currency placeOrderChor = new ChorPlaceOrder_Currency(
                        currencyService,
                        ctx.symChan(Service.FRONTEND.name(), frontendConn),
                        ctx.chanB(Service.PRODUCT_CATALOG.name()),
                        ctx.chanB(Service.SHIPPING.name()));

                placeOrderChor.placeOrder();
                ctx.log("[CURRENCY] PLACE_ORDER choreography completed");

                break;
            default:
                ctx.log("Invalid choreography " + ctx.session.choreographyName());
                break;
        }
    }
}
