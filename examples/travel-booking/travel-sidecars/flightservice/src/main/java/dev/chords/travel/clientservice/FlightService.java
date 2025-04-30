package dev.chords.travel.clientservice;

import choral.reactive.ChannelConfigurator;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import dev.chords.travel.choreographies.Airport;
import dev.chords.travel.choreographies.Coordinate;
import dev.chords.travel.choreographies.Flight;
import flights.FlightsGrpc;
import flights.FlightsGrpc.FlightsFutureStub;
import flights.FlightsOuterClass;
import flights.FlightsOuterClass.AirportSearchRequest;
import io.grpc.ManagedChannel;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class FlightService implements dev.chords.travel.choreographies.FlightService {

    protected ManagedChannel channel;
    protected FlightsFutureStub connection;
    protected Tracer tracer;
    protected Logger logger;

    public FlightService(InetSocketAddress address, OpenTelemetry telemetry) {
        channel = ChannelConfigurator.makeChannel(address, telemetry);

        this.connection = FlightsGrpc.newFutureStub(channel);
        this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);
        this.logger = new Logger(telemetry, FlightService.class.getName());
    }

    @Override
    public Airport nearestAirport(Coordinate location) {
        try {
            var request = AirportSearchRequest.newBuilder().setLat(location.latitude).setLon(location.longitude).build();

            Long t1 = System.nanoTime();
            var result = this.connection.nearestAirport(request).get(10, TimeUnit.SECONDS);
            Long t2 = System.nanoTime();

            System.out.println("SIDECAR RTT: " + (t2-t1));

            return new Airport(result.getId(), result.getName(), new Coordinate(result.getLat(), result.getLon()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Flight> searchFlight(String fromAirportID, String toAirportID, String date) {
        try {
            var request = FlightsOuterClass.SearchRequest.newBuilder().setFromAirport(fromAirportID).setToAirport(toAirportID).setDepartureDate(date).build();

            Long t1 = System.nanoTime();
            var result = this.connection.searchFlights(request).get(10, TimeUnit.SECONDS);
            Long t2 = System.nanoTime();

            System.out.println("SIDECAR RTT: " + (t2-t1));

            return result
                .getFlightsList()
                .stream()
                .map(flight -> new Flight(flight.getId(), flight.getFromAirport(), flight.getToAirport(), flight.getDepartureTime(), flight.getArrivalTime()))
                .collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void bookFlight(String flightID) {
        try {
            var request = FlightsOuterClass.BookingRequest.newBuilder().setId(flightID).build();

            this.connection.bookFlight(request).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
