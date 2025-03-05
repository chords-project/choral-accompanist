package dev.chords.travel.choreographies;

import java.io.Serializable;

public class BookTravelRequest@A implements Serializable@A {
    public final Coordinate@A from;
    public final Coordinate@A to;
    public final String@A startDate;
    public final String@A endDate;

    public BookTravelRequest(Coordinate@A from, Coordinate@A to, String@A startDate, String@A endDate) {
        this.from = from;
        this.to = to;
        this.startDate = startDate;
        this.endDate = endDate;
    }
}
