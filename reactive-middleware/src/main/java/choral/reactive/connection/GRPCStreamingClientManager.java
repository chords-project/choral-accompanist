package choral.reactive.connection;

import choral.reactive.tracing.JaegerConfiguration;
import choral_reactive.ChannelOuterClass;
import choral_reactive.StreamingChannelGrpc;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class GRPCStreamingClientManager implements ClientConnectionManager {

    private final ManagedChannel channel;
    private final StreamingChannelGrpc.StreamingChannelStub channelStub;
    private final OpenTelemetrySdk telemetry;
    private final String address;

    public GRPCStreamingClientManager(String address, OpenTelemetrySdk telemetry) throws URISyntaxException {
        this.address = address;
        this.telemetry = telemetry;

        URI uri = new URI(null, address, null, null, null).parseServerAuthority();
        InetSocketAddress socketAddr = new InetSocketAddress(uri.getHost(), uri.getPort());

        this.channel = ManagedChannelBuilder
            .forAddress(socketAddr.getHostString(), socketAddr.getPort())
            .usePlaintext()
            .build();

        this.channelStub = StreamingChannelGrpc.newStub(channel);
    }

    @Override
    public Connection makeConnection() {
        return new ClientConnection();
    }

    @Override
    public void close() throws IOException, InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public class ClientConnection implements Connection {

        Span connectionSpan;
        private StreamObserver<ChannelOuterClass.Message> streamObserver;

        private ClientConnection() {
            this.connectionSpan = telemetry.getTracer(JaegerConfiguration.TRACER_NAME)
                .spanBuilder("GRPCConnection: " + address)
                .setAttribute("address", address)
                .startSpan();

            this.streamObserver = channelStub.streamMessages(new StreamObserver<>() {
                @Override
                public void onNext(Empty value) {}

                @Override
                public void onError(Throwable t) {
                    throw new RuntimeException(t);
                }

                @Override
                public void onCompleted() {
                    throw new RuntimeException("Stream connection closed");
                }
            });
        }

        @Override
        public void sendMessage(Message msg) throws Exception {
            streamObserver.onNext(msg.toGrpcMessage());
            connectionSpan.addEvent("Message streamed to "+address,
                Attributes.builder()
                    .put("message", msg.toString())
                    .put("address", address)
                    .build());
        }

        @Override
        public void close() throws IOException {
            connectionSpan.end();
        }

        @Override
        public String toString() {
            return "GRPCStreamingConnection [ address=" + address + " ]";
        }
    }
}
