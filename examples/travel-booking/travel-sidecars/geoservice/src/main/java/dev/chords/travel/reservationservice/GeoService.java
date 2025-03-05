package dev.chords.travel.reservationservice;

import dev.chords.travel.choreographies.Coordinate;
import io.opentelemetry.api.OpenTelemetry;

import java.net.InetSocketAddress;
import java.util.List;

public class GeoService implements dev.chords.travel.choreographies.GeoService {

    public GeoService(InetSocketAddress address, OpenTelemetry telemetry) {}

    @Override
    public List<String> nearbyHotelIDs(Coordinate location) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }
}
