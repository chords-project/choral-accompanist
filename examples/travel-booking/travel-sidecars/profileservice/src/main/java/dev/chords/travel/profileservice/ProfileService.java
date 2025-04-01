package dev.chords.travel.profileservice;

import choral.reactive.ChannelConfigurator;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import dev.chords.travel.choreographies.Address;
import dev.chords.travel.choreographies.Hotel;
import dev.chords.travel.choreographies.Image;
import dev.chords.travel.choreographies.SerializableList;
import io.grpc.ManagedChannel;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import profile.ProfileGrpc;
import profile.ProfileOuterClass;
import search.SearchGrpc;
import search.SearchOuterClass;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class ProfileService implements dev.chords.travel.choreographies.ProfileService {

    protected ManagedChannel channel;
    protected ProfileGrpc.ProfileFutureStub connection;
    protected Tracer tracer;
    protected Logger logger;

    public ProfileService(InetSocketAddress address, OpenTelemetry telemetry) {
        channel = ChannelConfigurator.makeChannel(address, telemetry);

        this.connection = ProfileGrpc.newFutureStub(channel);
        this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);
        this.logger = new Logger(telemetry, ProfileService.class.getName());
    }

    @Override
    public SerializableList<Hotel> getProfiles(SerializableList<String> hotelID, String locale) {
        try {
            var result = this.connection.getProfiles(ProfileOuterClass.Request.newBuilder()
                    .addAllHotelIds(hotelID.list).build()
            ).get(10, TimeUnit.SECONDS);

            return new SerializableList<>(new ArrayList<>(
                    result.getHotelsList().stream().map(
                    hotel -> new Hotel(
                            hotel.getId(),
                            hotel.getName(),
                            hotel.getPhoneNumber(),
                            hotel.getDescription(),
                            new Address(
                                    hotel.getAddress().getStreetNumber(),
                                    hotel.getAddress().getStreetName(),
                                    hotel.getAddress().getCity(),
                                    hotel.getAddress().getCountry(),
                                    hotel.getAddress().getPostalCode(),
                                    (double) hotel.getAddress().getLat(),
                                    (double) hotel.getAddress().getLon()
                            ),
                            new ArrayList<>(hotel.getImagesList().stream().map(img -> new Image(img.getUrl(), img.getDefault())).toList())
                    )
                ).toList()
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
