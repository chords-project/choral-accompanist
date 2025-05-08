package dev.chords.travel.clientservice;

import choral.reactive.ReactiveServer;
import choral.reactive.SessionContext;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.Logger;
import dev.chords.travel.choreographies.*;
import dev.chords.travel.choreographies.TravelSession.Service;
import io.opentelemetry.api.OpenTelemetry;

import java.net.InetSocketAddress;

public class Main {

    private static ReservationService reservationService;

    private static Logger logger;

    public static void main(String[] args) throws Exception {
        OpenTelemetry telemetry = Tracing.initTracing("reservation-sidecar");
        logger = new Logger(telemetry, Main.class.getName());

        logger.info("Starting choral reservation sidecar");

        String rpcHost = System.getenv().getOrDefault("SERVICE_HOST", "reservation");
        int rpcPort = Integer.parseInt(System.getenv().getOrDefault("SERVICE_PORT", "8087"));
        reservationService = new ReservationService(new InetSocketAddress(rpcHost, rpcPort), telemetry);

        ReactiveServer server = new ReactiveServer(Service.RESERVATION.name(), telemetry, Main::handleNewSession);

        server.listen(ServiceResources.shared.reservation);
    }

    private static void handleNewSession(SessionContext ctx) throws Exception {
        TravelSession session = new TravelSession(ctx.session);

        switch (session.choreography) {
            case BOOK_TRAVEL:
                ctx.log("New BOOK_TRAVEL request");

                ChorBookTravel_Reservation bookTravelChor = new ChorBookTravel_Reservation(
                        ctx, reservationService
                );

                bookTravelChor.bookTravel();

                ctx.log("BOOK_TRAVEL choreography completed");

                break;
            case SEARCH_HOTELS:
                ctx.log("New BOOK_TRAVEL request");

                ChorSearchHotels_Reservation searchChor = new ChorSearchHotels_Reservation(
                        ctx, reservationService
                );

                searchChor.search();

                ctx.log("BOOK_TRAVEL choreography completed");

                break;
        }
    }
}
