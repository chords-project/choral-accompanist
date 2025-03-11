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
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;

public class Main extends ChoreographyGrpc.ChoreographyImplBase {

    private static ClientConnectionManager flightConn;
    private static ClientConnectionManager reservationConn;
    private static ReactiveServer server;
    private static Logger logger;

    private static GrpcServer grpcServer;
    private static OpenTelemetry telemetry;

    public static void main(String[] args) {
        telemetry = Tracing.initTracing("client-sidecar");
        logger = new Logger(telemetry, Main.class.getName());

        logger.info("Starting choral client sidecar");

        try {
            flightConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.flight, telemetry);
            reservationConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.reservation, telemetry);
        } catch (URISyntaxException | IOException e) {
            logger.exception("failed to start sidecar connections", e);
            throw new RuntimeException(e);
        }

        server = new ReactiveServer(Service.CLIENT.name(), telemetry, ctx -> {
            logger.warn("client server received new session from " + ctx.session.senderName() +
                    ", this should never happen and is ignored.");
        });

        grpcServer = new GrpcServer(telemetry, new GrpcServer.RequestHandler() {
            @Override
            public BookTravelResult bookTravel(BookTravelRequest req) throws Exception {
                return Main.bookTravel(req);
            }
        });

        try {
            grpcServer.start(8945);
        } catch (IOException e) {
            logger.exception("failed to start grpc server", e);
            throw new RuntimeException(e);
        }
        logger.info("Client gRPC server running on port 8945");

        try {
            server.listen(ServiceResources.shared.client);
        } catch (URISyntaxException | IOException e) {
            logger.exception("choral reactive server failed", e);
            throw new RuntimeException(e);
        }
    }

    private static BookTravelResult bookTravel(BookTravelRequest req) throws Exception {

        TravelSession session = TravelSession.makeSession(Choreography.BOOK_TRAVEL, Service.CLIENT);

        Span span = telemetry
                .getTracer(JaegerConfiguration.TRACER_NAME)
                .spanBuilder("BookTravel")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("choreography.session", session.toString())
                .startSpan();

        TelemetrySession telemetrySession = new TelemetrySession(telemetry, session, span);
        server.registerSession(session, telemetrySession);

        try (Scope scope = span.makeCurrent();
             ReactiveClient flightClient = new ReactiveClient(flightConn, Service.CLIENT.name(), telemetrySession);
             ReactiveClient reservationClient = new ReactiveClient(reservationConn, Service.CLIENT.name(), telemetrySession);) {

            telemetrySession.log("Initializing BOOK_TRAVEL choreography");

            var flightChan = new ReactiveSymChannel<>(flightClient.chanA(session),
                    server.chanB(session, Service.FLIGHT.name()));

            var reservationChan = new ReactiveSymChannel<>(reservationClient.chanA(session),
                    server.chanB(session, Service.RESERVATION.name()));

            ChorBookTravel_Client bookTravelChor = new ChorBookTravel_Client(
                    flightChan,
                    reservationChan
            );

            telemetrySession.log("Starting BOOK_TRAVEL choreography");
            var result = bookTravelChor.bookTravel(req);
            telemetrySession.log("Finished BOOK_TRAVEL choreography");

            return result;
        } catch (Exception e) {
            telemetrySession.recordException("Client BOOK_TRAVEL choreography failed", e, true);
            throw e;
        } finally {
            span.end();
        }
    }
}
