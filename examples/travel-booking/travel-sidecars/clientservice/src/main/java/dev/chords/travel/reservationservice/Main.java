package dev.chords.travel.reservationservice;

import choral.reactive.ReactiveServer;
import choral.reactive.ReactiveServer.SessionContext;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.Logger;
import dev.chords.travel.choreographies.*;
import io.opentelemetry.api.OpenTelemetry;
import dev.chords.travel.choreographies.TravelSession.Service;

public class Main {

    private static ClientConnectionManager flightConn;
    private static ClientConnectionManager reservationConn;
    private static Logger logger;

    public static void main(String[] args) throws Exception {
        OpenTelemetry telemetry = Tracing.initTracing("ClientService");
        logger = new Logger(telemetry, Main.class.getName());

        logger.info("Starting choral client service");

        flightConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.flight, telemetry);
        reservationConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.reservation, telemetry);

        ReactiveServer server = new ReactiveServer(Service.CLIENT.name(), telemetry,
                Main::handleNewSession);

        server.listen(ServiceResources.shared.client);
    }

    private static void handleNewSession(SessionContext ctx)
            throws Exception {
        TravelSession session = new TravelSession(ctx.session);

        switch (session.choreography) {
            case BOOK_TRAVEL:
                ctx.log("New BOOK_TRAVEL request");

                ChorBookTravel_Client bookTravelChor = new ChorBookTravel_Client(
                        ctx.symChan(Service.FLIGHT.name(), flightConn),
                        ctx.symChan(Service.RESERVATION.name(), reservationConn)
                );

                bookTravelChor.bookTravel(null);

                ctx.log("BOOK_TRAVEL choreography completed");

                break;
            default:
                ctx.log("Invalid choreography " + ctx.session.choreographyName());
                break;
        }
    }
}
