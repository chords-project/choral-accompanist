package dev.chords.travel.choreographies;

import java.io.Serializable;

public class Coordinate@A implements Serializable@A {
    public final double@A latitude;
    public final double@A longitude;

    public Coordinate(double@A latitude, double@A longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
}