package dev.chords.travel.clientservice;

import choral.reactive.ChannelConfigurator;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import geo.GeoGrpc;
import io.grpc.ManagedChannel;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import reservation.ReservationGrpc;
import reservation.ReservationOuterClass;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class ReservationService implements dev.chords.travel.choreographies.ReservationService {

    protected ManagedChannel channel;
    protected ReservationGrpc.ReservationFutureStub connection;
    protected Tracer tracer;
    protected Logger logger;


    public ReservationService(InetSocketAddress address, OpenTelemetry telemetry) {
        channel = ChannelConfigurator.makeChannel(address, telemetry);

        this.connection = ReservationGrpc.newFutureStub(channel);
        this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);
        this.logger = new Logger(telemetry, ReservationService.class.getName());
    }

    @Override
    public void makeReservation(String customerName, String hotelID, String inDate, String outDate) {
        try {
            var request = ReservationOuterClass.Request.newBuilder()
                    .setCustomerName(customerName)
                    .addHotelId(hotelID)
                    .setInDate(inDate)
                    .setOutDate(outDate)
                    .build();

            this.connection.makeReservation(request).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
