package dev.chords.travel.choreographies;

import java.util.List;

public interface FlightService@A {
    Airport@A nearestAirport(Coordinate@A location);
    List@A<Flight> searchFlight(String@A fromAirportID, String@A toAirportID, String@A date);
    void bookFlight(String@A flightID);
}