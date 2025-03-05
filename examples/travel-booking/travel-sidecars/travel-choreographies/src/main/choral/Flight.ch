package dev.chords.travel.choreographies;

import java.io.Serializable;

public class Flight@A implements Serializable@A {
    public final String@A id;
    public final String@A fromAirport;
    public final String@A toAirport;
    public final String@A departureTime;
    public final String@A arrivalTime;

    public Flight(String@A id, String@A fromAirport, String@A toAirport, String@A departureTime, String@A arrivalTime) {
        this.id = id;
        this.fromAirport = fromAirport;
        this.toAirport = toAirport;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
    }
}