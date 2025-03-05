package dev.chords.travel.reservationservice;

import dev.chords.travel.choreographies.Coordinate;
import io.opentelemetry.api.OpenTelemetry;

import java.net.InetSocketAddress;
import java.util.List;

public class ReservationService implements dev.chords.travel.choreographies.ReservationService {

    public ReservationService(InetSocketAddress address, OpenTelemetry telemetry) {}

    @Override
    public void makeReservation(String customerName, String hotelID, String inDate, String outDate) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }
}
