package dev.chords.travel.clientservice;

import choral.reactive.tracing.Logger;
import choreography.ChoreographyGrpc;
import choreography.ChoreographyOuterClass;
import dev.chords.travel.choreographies.*;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class GrpcServer {

    private Server server;
    private OpenTelemetry telemetry;
    private Logger logger;
    private RequestHandler requestHandler;

    public interface RequestHandler {
        BookTravelResult bookTravel(BookTravelRequest req) throws Exception;
        ArrayList<Hotel> searchHotels(SearchHotelsRequest request) throws Exception;
    }

    public GrpcServer(OpenTelemetry telemetry, RequestHandler requestHandler) {
        this.telemetry = telemetry;
        this.logger = new Logger(telemetry, GrpcServer.class.getName());
        this.requestHandler = requestHandler;
    }

    public void start(int port) throws IOException {
        server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create()).addService(new ChoreographyImpl()).build().start();

        Runtime.getRuntime()
            .addShutdownHook(
                new Thread(() -> {
                    // Use stderr here since the logger may have been reset by its JVM shutdown
                    // hook.
                    logger.info("GrpcServer: shutting down gRPC server since JVM is shutting down");
                    try {
                        GrpcServer.this.stop();
                    } catch (InterruptedException e) {
                        e.printStackTrace(System.err);
                    }
                    logger.info("GrpcServer: server shut down");
                })
            );
    }

    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon
     * threads.
     */
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    class ChoreographyImpl extends ChoreographyGrpc.ChoreographyImplBase {

        @Override
        public void bookTravel(ChoreographyOuterClass.BookTravelRequest request, StreamObserver<ChoreographyOuterClass.BookTravelResult> responseObserver) {
            Coordinate from = new Coordinate(request.getFrom().getLatitude(), request.getFrom().getLongitude());
            Coordinate to = new Coordinate(request.getTo().getLatitude(), request.getTo().getLongitude());
            BookTravelRequest req = new BookTravelRequest(from, to, request.getStartDate(), request.getEndDate());

            BookTravelResult res;
            try {
                res = requestHandler.bookTravel(req);
            } catch (Exception e) {
                logger.exception("Error during BookTravel gRPC request", e);
                responseObserver.onError(e);
                return;
            }

            responseObserver.onNext(
                ChoreographyOuterClass.BookTravelResult.newBuilder()
                    .setOutFlight(flightToGrpc(res.outFlight))
                    .setHomeFlight(flightToGrpc(res.homeFlight))
                    .setHotelId(res.hotelID)
                    .build()
            );
            responseObserver.onCompleted();
        }

        @Override
        public void search(ChoreographyOuterClass.SearchRequest request, StreamObserver<ChoreographyOuterClass.SearchResult> responseObserver) {
            super.search(request, responseObserver);

            SearchHotelsRequest req = new SearchHotelsRequest(request.getInDate(), request.getOutDate(), request.getLat(), request.getLon());
            ArrayList<Hotel> hotels;

            try {
                hotels = requestHandler.searchHotels(req);
            } catch (Exception e) {
                logger.exception("Error during SearchHotels gRPC request", e);
                responseObserver.onError(e);
                return;
            }

            responseObserver.onNext(
                    ChoreographyOuterClass.SearchResult.newBuilder()
                            .addAllHotels(hotels.stream().map(GrpcServer::hotelToGrpc).toList())
                            .build()
            );
            responseObserver.onCompleted();
        }
    }

    private static ChoreographyOuterClass.Flight flightToGrpc(Flight flight) {
        return ChoreographyOuterClass.Flight.newBuilder()
            .setId(flight.id)
            .setFromAirport(flight.fromAirport)
            .setToAirport(flight.toAirport)
            .setDepartureTime(flight.departureTime)
            .setArrivalTime(flight.arrivalTime)
            .build();
    }

    private static ChoreographyOuterClass.Hotel hotelToGrpc(Hotel hotel) {
        return ChoreographyOuterClass.Hotel.newBuilder()
                .setId(hotel.id)
                .setName(hotel.name)
                .setPhoneNumber(hotel.phoneNumber)
                .setDescription(hotel.description)
                .setAddress(
                        ChoreographyOuterClass.Address.newBuilder()
                                .setStreetNumber(hotel.address.streetNumber)
                                .setStreetName(hotel.address.streetName)
                                .setCity(hotel.address.city)
                                .setCountry(hotel.address.country)
                                .setPostalCode(hotel.address.postalCode)
                                .setLat(hotel.address.lat)
                                .setLon(hotel.address.lon)
                )
                .addAllImages(
                        hotel.images.stream().map(img ->
                                ChoreographyOuterClass.Image.newBuilder()
                                        .setUrl(img.url)
                                        .setIsDefault(img.isDefault)
                                        .build()
                        ).toList()
                )
                .build();
    }

    private static Hotel grpcToHotel(ChoreographyOuterClass.Hotel hotel) {

        Address address = new Address(
                hotel.getAddress().getStreetNumber(),
                hotel.getAddress().getStreetName(),
                hotel.getAddress().getCity(),
                hotel.getAddress().getCountry(),
                hotel.getAddress().getPostalCode(),
                hotel.getAddress().getLat(),
                hotel.getAddress().getLon());

        List<Image> images = hotel.getImagesList().stream()
                .map(img -> new Image(img.getUrl(), img.getIsDefault()))
                .toList();

        return new Hotel(hotel.getId(), hotel.getName(), hotel.getPhoneNumber(), hotel.getDescription(), address, new ArrayList<>(images));
    }
}
