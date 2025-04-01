package dev.chords.travel.choreographies;

import java.io.Serializable;

public class SearchHotelsRequest@A implements Serializable@A {
    public final String@A inDate;
    public final String@A outDate;
    public final Double@A lat;
    public final Double@A lon;

    public SearchHotelsRequest(String@A inDate, String@A outDate, Double@A lat, Double@A lon) {
        this.inDate = inDate;
        this.outDate = outDate;
        this.lat = lat;
        this.lon = lon;
    }
}