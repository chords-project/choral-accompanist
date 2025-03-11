package dev.chords.travel.clientservice;

import choral.reactive.ChannelConfigurator;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import dev.chords.travel.choreographies.Coordinate;
import flights.FlightsGrpc;
import geo.GeoGrpc;
import geo.GeoOuterClass;
import io.grpc.ManagedChannel;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class GeoService implements dev.chords.travel.choreographies.GeoService {

    protected ManagedChannel channel;
    protected GeoGrpc.GeoFutureStub connection;
    protected Tracer tracer;
    protected Logger logger;

    public GeoService(InetSocketAddress address, OpenTelemetry telemetry) {
        channel = ChannelConfigurator.makeChannel(address, telemetry);

        this.connection = GeoGrpc.newFutureStub(channel);
        this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);
        this.logger = new Logger(telemetry, GeoService.class.getName());
    }

    @Override
    public List<String> nearbyHotelIDs(Coordinate location) {
        try {
            var result = this.connection.nearby(GeoOuterClass.Request.newBuilder()
                    .setLat((float)location.latitude)
                    .setLon((float)location.longitude)
                    .build()
            ).get(10, TimeUnit.SECONDS);

            return result.getHotelIdsList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
