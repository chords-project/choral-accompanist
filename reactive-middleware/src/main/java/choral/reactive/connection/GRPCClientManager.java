package choral.reactive.connection;

import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import choral_reactive.ChannelGrpc;
import choral_reactive.ChannelGrpc.ChannelFutureStub;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class GRPCClientManager implements ClientConnectionManager {

    private final ManagedChannel channel;
    private final ChannelFutureStub futureStub;
    private final OpenTelemetry telemetry;
    private final Logger logger;
    private final String address;
    private final DoubleHistogram sendHistogram;

    public GRPCClientManager(String address, OpenTelemetry telemetry) throws URISyntaxException {
        this.address = address;
        this.telemetry = telemetry;
        this.logger = new Logger(telemetry, GRPCClientManager.class.getName());

        URI uri = new URI(null, address, null, null, null).parseServerAuthority();
        InetSocketAddress socketAddr = new InetSocketAddress(uri.getHost(), uri.getPort());

        this.channel = ManagedChannelBuilder
                .forAddress(socketAddr.getHostString(), socketAddr.getPort())
                .usePlaintext()
                .build();

        this.futureStub = ChannelGrpc
            .newFutureStub(channel);
            //.withDeadlineAfter(10, TimeUnit.SECONDS);

        this.sendHistogram = telemetry.getMeter(JaegerConfiguration.TRACER_NAME)
            .histogramBuilder("choral.reactive.grpc-client.send-duration")
            .setDescription("Duration for sending a message")
            .setUnit("ms")
            .build();
    }

    @Override
    public Connection makeConnection() {
        logger.debug("Connect to gRPC server " + address);
        return new ClientConnection();
    }

    @Override
    public void close() throws IOException, InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public class ClientConnection implements Connection {

        Span connectionSpan;

        private ClientConnection() {
            this.connectionSpan = telemetry.getTracer(JaegerConfiguration.TRACER_NAME)
                    .spanBuilder("GRPCConnection: " + address)
                    .setAttribute("address", address)
                    .startSpan();
        }

        @Override
        public void sendMessage(Message msg) throws Exception {
            var result = futureStub.sendMessage(msg.toGrpcMessage());

            Attributes attributes = Attributes.builder()
                .put("message", msg.toString())
                .put("address", address)
                .build();

            long startTime = System.nanoTime();

            result.addListener(() -> {
                try {
                    result.get();

                    double duration = (System.nanoTime() - startTime) / 1_000_000.0;

                    connectionSpan.addEvent("Message sent to "+address+" ("+(long)duration+" ms)", attributes);

                    sendHistogram.record(
                        duration,
                        Attributes.builder()
                            .put("address", address)
                            .build()
                    );
                } catch (Exception e) {
                    connectionSpan.setAttribute("error", true);
                    connectionSpan.recordException(e);
                }
            }, Executors.newVirtualThreadPerTaskExecutor());
        }

        @Override
        public void close() throws IOException {
            connectionSpan.end();
        }

        @Override
        public String toString() {
            return "GRPCConnection [ address=" + address + " ]";
        }
    }
}
