package dev.chords.travel.choreographies;

import java.util.ArrayList;

public interface ReservationService@A {
    void makeReservation(String@A customerName, String@A hotelID, String@A inDate, String@A outDate);
    SerializableList@A<String> checkAvailability(String@A customerName, SerializableList@A<String> hotelId, String@A inDate, String@A outDate, int@A roomNumber);
}