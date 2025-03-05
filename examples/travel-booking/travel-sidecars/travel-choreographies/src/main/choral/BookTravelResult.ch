package dev.chords.travel.choreographies;

public class BookTravelResult@A {
    public final Flight@A outFlight;
    public final Flight@A homeFlight;
    public final String@A hotelID;

    public BookTravelResult(Flight@A outFlight, Flight@A homeFlight, String@A hotelID) {
        this.outFlight = outFlight;
        this.homeFlight = homeFlight;
        this.hotelID = hotelID;
    }
}