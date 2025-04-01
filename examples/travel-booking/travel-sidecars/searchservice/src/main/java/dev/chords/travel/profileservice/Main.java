package dev.chords.travel.profileservice;

import choral.reactive.ReactiveServer;
import choral.reactive.ReactiveServer.SessionContext;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.Logger;
import dev.chords.travel.choreographies.ChorSearchHotels_Search;
import dev.chords.travel.choreographies.ServiceResources;
import dev.chords.travel.choreographies.Tracing;
import dev.chords.travel.choreographies.TravelSession;
import dev.chords.travel.choreographies.TravelSession.Service;
import io.opentelemetry.api.OpenTelemetry;
import java.net.InetSocketAddress;

public class Main {

    private static SearchService searchService;

    private static ClientConnectionManager reservationConn;
    private static Logger logger;

    public static void main(String[] args) throws Exception {
        OpenTelemetry telemetry = Tracing.initTracing("search-sidecar");
        logger = new Logger(telemetry, Main.class.getName());

        logger.info("Starting choral search sidecar");

        String rpcHost = System.getenv().getOrDefault("SERVICE_HOST", "search");
        int rpcPort = Integer.parseInt(System.getenv().getOrDefault("SERVICE_PORT", "8083"));
        searchService = new SearchService(new InetSocketAddress(rpcHost, rpcPort), telemetry);

        reservationConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.reservation, telemetry);

        ReactiveServer server = new ReactiveServer(Service.SEARCH.name(), telemetry, Main::handleNewSession);

        server.listen(ServiceResources.shared.search);
    }

    private static void handleNewSession(SessionContext ctx) throws Exception {
        TravelSession session = new TravelSession(ctx.session);

        switch (session.choreography) {
            case SEARCH_HOTELS:
                ctx.log("New SEARCH_HOTELS request");

                ChorSearchHotels_Search searchHotelsChor = new ChorSearchHotels_Search(
                        searchService,
                        ctx.chanB(Service.CLIENT.name()),
                        ctx.chanA(reservationConn)
                );

                searchHotelsChor.search();

                ctx.log("SEARCH_HOTELS choreography completed");

                break;
            default:
                ctx.log("Invalid choreography " + ctx.session.choreographyName());
                break;
        }
    }
}
