package dev.chords.travel.clientservice;

import choral.reactive.ReactiveServer;
import choral.reactive.ReactiveServer.SessionContext;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.Logger;
import dev.chords.travel.choreographies.*;
import io.opentelemetry.api.OpenTelemetry;
import dev.chords.travel.choreographies.TravelSession.Service;
import java.net.InetSocketAddress;

public class Main {

    private static ReservationService reservationService;

    private static ClientConnectionManager clientConn;
    private static Logger logger;

    public static void main(String[] args) throws Exception {
        OpenTelemetry telemetry = Tracing.initTracing("reservation-sidecar");
        logger = new Logger(telemetry, Main.class.getName());

        logger.info("Starting choral reservation sidecar");

        int rpcPort = Integer.parseInt(System.getenv().getOrDefault("SERVICE_PORT", "8090"));
        reservationService = new ReservationService(new InetSocketAddress("localhost", rpcPort), telemetry);

        clientConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.client, telemetry);

        ReactiveServer server = new ReactiveServer(Service.GEO.name(), telemetry,
                Main::handleNewSession);

        server.listen(ServiceResources.shared.geo);
    }

    private static void handleNewSession(SessionContext ctx)
            throws Exception {
        TravelSession session = new TravelSession(ctx.session);

        switch (session.choreography) {
            case BOOK_TRAVEL:
                ctx.log("New BOOK_TRAVEL request");

                ChorBookTravel_Reservation bookTravelChor = new ChorBookTravel_Reservation(
                        reservationService,
                        ctx.chanB(Service.GEO.name()),
                        ctx.symChan(Service.CLIENT.name(), clientConn)
                );

                bookTravelChor.bookTravel();

                ctx.log("BOOK_TRAVEL choreography completed");

                break;
            default:
                ctx.log("Invalid choreography " + ctx.session.choreographyName());
                break;
        }
    }
}
