package choral.reactive.connection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Empty;

import choral_reactive.ChannelOuterClass.Message;
import choral_reactive.StreamingChannelGrpc.StreamingChannelImplBase;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import io.grpc.protobuf.services.HealthStatusManager;
import io.opentelemetry.sdk.OpenTelemetrySdk;

public class GRPCStreamingServerManager implements ServerConnectionManager {

    private final ServerEvents serverEvents;
    private Server server;

    public GRPCStreamingServerManager(ServerEvents serverEvents, OpenTelemetrySdk telemetry) {
        this.serverEvents = serverEvents;
    }

    @Override
    public void listen(String address) throws URISyntaxException, IOException {
        URI uri = new URI(null, address, null, null, null).parseServerAuthority();
        InetSocketAddress addr = new InetSocketAddress(uri.getHost(), uri.getPort());

        HealthStatusManager health = new HealthStatusManager();

        server = Grpc.newServerBuilderForPort(addr.getPort(), InsecureServerCredentials.create())
            .addService(new ChannelGrpcImpl())
            .addService(health.getHealthService())
            .build()
            .start();

        try {
            server.awaitTermination();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() throws IOException {
        if (server != null) {
            try {
                server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private class ChannelGrpcImpl extends StreamingChannelImplBase {

        @Override
        public StreamObserver<Message> streamMessages(StreamObserver<Empty> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(Message value) {
                    try {
                        var message = new choral.reactive.connection.Message(value);
                        serverEvents.messageReceived(message);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                }

                @Override
                public void onCompleted() {
                    // End and close connection with the client
                    responseObserver.onNext(Empty.getDefaultInstance());
                    responseObserver.onCompleted();
                }
            };
        }

        //        @Override
//        public void sendMessage(Message request, StreamObserver<Empty> responseObserver) {
//            try {
//                var message = new choral.reactive.connection.Message(request);
//                serverEvents.messageReceived(message);
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//
//            responseObserver.onNext(Empty.getDefaultInstance());
//            responseObserver.onCompleted();
//        }
    }

}
