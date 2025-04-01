package dev.chords.travel.profileservice;

import choral.reactive.ChannelConfigurator;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import dev.chords.travel.choreographies.SerializableList;
import io.grpc.ManagedChannel;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import search.SearchGrpc;
import search.SearchOuterClass;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class SearchService implements dev.chords.travel.choreographies.SearchService {

    protected ManagedChannel channel;
    protected SearchGrpc.SearchFutureStub connection;
    protected Tracer tracer;
    protected Logger logger;

    public SearchService(InetSocketAddress address, OpenTelemetry telemetry) {
        channel = ChannelConfigurator.makeChannel(address, telemetry);

        this.connection = SearchGrpc.newFutureStub(channel);
        this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);
        this.logger = new Logger(telemetry, SearchService.class.getName());
    }

    @Override
    public SerializableList<String> nearby(Double lat, Double lon, String inDate, String outDate) {
        try {
            var result = this.connection.nearby(SearchOuterClass.NearbyRequest.newBuilder()
                    .setLat(lat.floatValue()).setLon(lon.floatValue()).setInDate(inDate).setOutDate(outDate).build()
            ).get(10, TimeUnit.SECONDS);

            return new SerializableList<>(new ArrayList<>(result.getHotelIdsList()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
