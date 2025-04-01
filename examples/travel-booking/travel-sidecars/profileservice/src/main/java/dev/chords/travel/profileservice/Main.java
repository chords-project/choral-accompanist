package dev.chords.travel.profileservice;

import choral.reactive.ReactiveServer;
import choral.reactive.ReactiveServer.SessionContext;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.Logger;
import dev.chords.travel.choreographies.*;
import dev.chords.travel.choreographies.TravelSession.Service;
import io.opentelemetry.api.OpenTelemetry;
import java.net.InetSocketAddress;

public class Main {

    private static ProfileService profileService;

    private static ClientConnectionManager clientConn;
    private static Logger logger;

    public static void main(String[] args) throws Exception {
        OpenTelemetry telemetry = Tracing.initTracing("profile-sidecar");
        logger = new Logger(telemetry, Main.class.getName());

        logger.info("Starting choral profile sidecar");

        String rpcHost = System.getenv().getOrDefault("SERVICE_HOST", "profile");
        int rpcPort = Integer.parseInt(System.getenv().getOrDefault("SERVICE_PORT", "8083"));
        profileService = new ProfileService(new InetSocketAddress(rpcHost, rpcPort), telemetry);

        clientConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.client, telemetry);

        ReactiveServer server = new ReactiveServer(Service.PROFILE.name(), telemetry, Main::handleNewSession);

        server.listen(ServiceResources.shared.profile);
    }

    private static void handleNewSession(SessionContext ctx) throws Exception {
        TravelSession session = new TravelSession(ctx.session);

        switch (session.choreography) {
            case SEARCH_HOTELS:
                ctx.log("New SEARCH_HOTELS request");

                ChorSearchHotels_Profile searchHotelsChor = new ChorSearchHotels_Profile(
                        profileService,
                        ctx.chanB(Service.RESERVATION.name()),
                        ctx.chanA(clientConn)
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
