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

    private static GeoService geoService;

    private static ClientConnectionManager flightConn;
    private static ClientConnectionManager reservationConn;
    private static Logger logger;

    public static void main(String[] args) throws Exception {
        OpenTelemetry telemetry = Tracing.initTracing("FlightService");
        logger = new Logger(telemetry, Main.class.getName());

        logger.info("Starting choral flight service");

        int rpcPort = Integer.parseInt(System.getenv().getOrDefault("SERVICE_PORT", "8090"));
        geoService = new GeoService(new InetSocketAddress("localhost", rpcPort), telemetry);

        flightConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.flight, telemetry);
        reservationConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.reservation, telemetry);

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

                ChorBookTravel_Geo bookTravelChor = new ChorBookTravel_Geo(
                        geoService,
                        ctx.symChan(Service.FLIGHT.name(), flightConn),
                        ctx.symChan(Service.RESERVATION.name(), reservationConn)
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
