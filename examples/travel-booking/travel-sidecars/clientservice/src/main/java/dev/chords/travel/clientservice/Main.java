package dev.chords.travel.clientservice;

import choral.reactive.ReactiveClient;
import choral.reactive.ReactiveServer;
import choral.reactive.ReactiveSymChannel;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import choral.reactive.tracing.TelemetrySession;
import choreography.ChoreographyGrpc;
import dev.chords.travel.choreographies.*;
import dev.chords.travel.choreographies.TravelSession.Choreography;
import dev.chords.travel.choreographies.TravelSession.Service;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;

public class Main extends ChoreographyGrpc.ChoreographyImplBase {

    private static ClientConnectionManager flightConn;
    private static ClientConnectionManager geoConn;
    private static ClientConnectionManager reservationConn;
    private static ClientConnectionManager searchConn;
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
            geoConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.geo, telemetry);
            reservationConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.reservation, telemetry);
            searchConn = ClientConnectionManager.makeConnectionManager(ServiceResources.shared.search, telemetry);
        } catch (URISyntaxException | IOException e) {
            logger.exception("failed to start sidecar connections", e);
            throw new RuntimeException(e);
        }

        server = new ReactiveServer(Service.CLIENT.name(), telemetry, ctx -> {
            logger.warn("client server received new session from " + ctx.session.senderName() + ", this should never happen and is ignored.");
        });

        grpcServer = new GrpcServer(
                telemetry,
                new GrpcServer.RequestHandler() {
                    @Override
                    public BookTravelResult bookTravel(BookTravelRequest req) throws Exception {
                        return Main.bookTravel(req);
                    }

                    @Override
                    public ArrayList<Hotel> searchHotels(SearchHotelsRequest request) throws Exception {
                        return Main.searchHotels(request);
                    }
                }
        );

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

    private static ArrayList<Hotel> searchHotels(SearchHotelsRequest request) throws Exception {
        TravelSession session = TravelSession.makeSession(Choreography.SEARCH_HOTELS, Service.CLIENT);

        Span span = telemetry
                .getTracer(JaegerConfiguration.TRACER_NAME)
                .spanBuilder("SearchHotels")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("choreography.session", session.toString())
                .startSpan();

        TelemetrySession telemetrySession = new TelemetrySession(telemetry, session, span);
        server.registerSession(session, telemetrySession);

        try (
                Scope scope = span.makeCurrent();
                ReactiveClient searchClient = new ReactiveClient(searchConn, Service.CLIENT.name(), telemetrySession);
        ) {
            telemetrySession.log("Initializing SEARCH_HOTELS choreography");

            ChorSearchHotels_Client searchHotelsChor = new ChorSearchHotels_Client(
                    searchClient.chanA(session),
                    server.chanB(session, Service.PROFILE.name())
            );

            telemetrySession.log("Starting SEARCH_HOTELS choreography");
            var result = searchHotelsChor.search(request);
            telemetrySession.log("Finished SEARCH_HOTELS choreography");

            return result;
        } catch (Exception e) {
            telemetrySession.recordException("Client SEARCH_HOTELS choreography failed", e, true);
            throw e;
        } finally {
            span.end();
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

        try (
                Scope scope = span.makeCurrent();
                ReactiveClient flightClient = new ReactiveClient(flightConn, Service.CLIENT.name(), telemetrySession);
                ReactiveClient geoClient = new ReactiveClient(geoConn, Service.CLIENT.name(), telemetrySession);
                ReactiveClient reservationClient = new ReactiveClient(reservationConn, Service.CLIENT.name(), telemetrySession);
        ) {
            telemetrySession.log("Initializing BOOK_TRAVEL choreography");

            var flightChan = new ReactiveSymChannel<>(flightClient.chanA(session), server.chanB(session, Service.FLIGHT.name()));
            var reservationChan = new ReactiveSymChannel<>(reservationClient.chanA(session), server.chanB(session, Service.RESERVATION.name()));

            var geoChan = new ReactiveSymChannel<>(geoClient.chanA(session), server.chanB(session, Service.GEO.name()));

            ChorBookTravel_Client bookTravelChor = new ChorBookTravel_Client(flightChan, geoChan, reservationChan);

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
