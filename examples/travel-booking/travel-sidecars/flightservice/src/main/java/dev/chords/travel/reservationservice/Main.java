package dev.chords.travel.reservationservice;

import choral.reactive.ReactiveServer;
import choral.reactive.ReactiveServer.SessionContext;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.Logger;
import dev.chords.travel.choreographies.ChorBookTravel_Flight;
import dev.chords.travel.choreographies.ServiceResources;
import dev.chords.travel.choreographies.Tracing;
import dev.chords.travel.choreographies.TravelSession;
import io.opentelemetry.api.OpenTelemetry;
import dev.chords.travel.choreographies.TravelSession.Service;
import java.net.InetSocketAddress;

public class Main {

    private static FlightService flightService;

    private static ClientConnectionManager clientConn;
    private static ClientConnectionManager geoConn;
    private static Logger logger;

    public static void main(String[] args) throws Exception {
        OpenTelemetry telemetry = Tracing.initTracing("FlightService");
        logger = new Logger(telemetry, Main.class.getName());

        logger.info("Starting choral flight service");

        int rpcPort = Integer.parseInt(System.getenv().getOrDefault("SERVICE_PORT", "8090"));
        flightService = new FlightService(new InetSocketAddress("localhost", rpcPort), telemetry);

        clientConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.client, telemetry);
        geoConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.geo, telemetry);

        ReactiveServer server = new ReactiveServer(Service.FLIGHT.name(), telemetry,
                Main::handleNewSession);

        server.listen(ServiceResources.shared.flight);
    }

    private static void handleNewSession(SessionContext ctx)
            throws Exception {
        TravelSession session = new TravelSession(ctx.session);

        switch (session.choreography) {
            case BOOK_TRAVEL:
                ctx.log("New BOOK_TRAVEL request");

                ChorBookTravel_Flight bookTravelChor = new ChorBookTravel_Flight(
                        flightService,
                        ctx.symChan(Service.CLIENT.name(), clientConn),
                        ctx.chanA(geoConn)
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
