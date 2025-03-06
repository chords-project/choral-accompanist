package dev.chords.travel.clientservice;

import dev.chords.travel.choreographies.Airport;
import dev.chords.travel.choreographies.Coordinate;
import dev.chords.travel.choreographies.Flight;
import io.opentelemetry.api.OpenTelemetry;

import java.net.InetSocketAddress;
import java.util.List;

public class FlightService implements dev.chords.travel.choreographies.FlightService {

    public FlightService(InetSocketAddress address, OpenTelemetry telemetry) {}

    @Override
    public Airport nearestAirport(Coordinate location) {
        throw new UnsupportedOperationException("Not implemeneted yet.");
    }

    @Override
    public List<Flight> searchFlight(String fromAirportID, String toAirportID, String date) {
        throw new UnsupportedOperationException("Not implemeneted yet.");
    }

    @Override
    public void bookFlight(String flightID) {
        throw new UnsupportedOperationException("Not implemeneted yet.");
    }
}
