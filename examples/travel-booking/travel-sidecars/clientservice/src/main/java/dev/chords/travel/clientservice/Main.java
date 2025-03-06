package dev.chords.travel.clientservice;

import choral.reactive.*;
import choral.reactive.ReactiveServer.SessionContext;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import choral.reactive.tracing.TelemetrySession;
import choreography.ChoreographyGrpc;
import dev.chords.travel.choreographies.*;
import dev.chords.travel.choreographies.TravelSession.Choreography;
import io.opentelemetry.api.OpenTelemetry;
import dev.chords.travel.choreographies.TravelSession.Service;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.io.Serializable;

public class Main extends ChoreographyGrpc.ChoreographyImplBase {

    private static ClientConnectionManager flightConn;
    private static ClientConnectionManager reservationConn;
    private static ReactiveServer server;
    private static Logger logger;

    private static GrpcServer grpcServer;
    private static OpenTelemetry telemetry;

    public static void main(String[] args) throws Exception {
        telemetry = Tracing.initTracing("ClientService");
        logger = new Logger(telemetry, Main.class.getName());

        logger.info("Starting choral client service");

        flightConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.flight, telemetry);
        reservationConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.reservation, telemetry);

        server = new ReactiveServer(Service.CLIENT.name(), telemetry, ctx -> {
            throw new Exception("Client server will never start new session");
        });

        grpcServer = new GrpcServer(new GrpcServer.RequestHandler() {
            @Override
            public BookTravelResult bookTravel(BookTravelRequest req) throws Exception {
                return Main.bookTravel(req);
            }
        });
        grpcServer.start(8945);
        logger.info("Client gRPC server running on port 8945");

        server.listen(ServiceResources.shared.client);
    }

    private static BookTravelResult bookTravel(BookTravelRequest req) throws Exception {

        TravelSession session = TravelSession.makeSession(Choreography.BOOK_TRAVEL, Service.CLIENT);

        Span span = telemetry.getTracer(JaegerConfiguration.TRACER_NAME)
                .spanBuilder("BookTravel").startSpan();

        try (Scope scope = span.makeCurrent()) {
            TelemetrySession telemetrySession = new TelemetrySession(telemetry, session, span);

            ReactiveClient flightClient = new ReactiveClient(flightConn, Service.FLIGHT.name(), telemetrySession);
            ReactiveChannel_A<Serializable> flightChanA = new ReactiveChannel_A<>(session, flightClient, telemetrySession);
            ReactiveChannel_B<Serializable> flightChanB = new ReactiveChannel_B<>(session.replacingSender(Service.FLIGHT.name()), server, telemetrySession);
            ReactiveSymChannel<Serializable> flightChan = new ReactiveSymChannel<>(flightChanA, flightChanB);

            ReactiveClient reservationClient = new ReactiveClient(reservationConn, Service.RESERVATION.name(), telemetrySession);
            ReactiveChannel_A<Serializable> reservationChanA = new ReactiveChannel_A<>(session, reservationClient, telemetrySession);
            ReactiveChannel_B<Serializable> reservationChanB = new ReactiveChannel_B<>(session.replacingSender(Service.RESERVATION.name()), server, telemetrySession);
            ReactiveSymChannel<Serializable> reservationChan = new ReactiveSymChannel<>(reservationChanA, reservationChanB);

            ChorBookTravel_Client bookTravelChor = new ChorBookTravel_Client(
                    flightChan,
                    reservationChan
            );

            return bookTravelChor.bookTravel(req);
        }
    }
}
