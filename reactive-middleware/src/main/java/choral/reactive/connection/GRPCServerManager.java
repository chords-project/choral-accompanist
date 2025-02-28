package choral.reactive.connection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import choral.reactive.tracing.Logger;
import com.google.protobuf.Empty;

import choral_reactive.ChannelGrpc.ChannelImplBase;
import choral_reactive.ChannelOuterClass.Message;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import io.grpc.protobuf.services.HealthStatusManager;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

public class GRPCServerManager implements ServerConnectionManager {

    private final ServerEvents serverEvents;
    private Server server;
    private final Logger logger;

    public GRPCServerManager(ServerEvents serverEvents, OpenTelemetry telemetry) {
        this.serverEvents = serverEvents;
        this.logger = new Logger(telemetry, GRPCServerManager.class.getName());
    }

    @Override
    public void listen(String address) throws URISyntaxException, IOException {
        logger.info("Starting gRPC server on " + address);

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
        logger.info("Shutting down gRPC server");

        if (server != null) {
            try {
                server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private class ChannelGrpcImpl extends ChannelImplBase {

        @Override
        public void sendMessage(Message request, StreamObserver<Empty> responseObserver) {
            logger.debug("Received message on gRPC server");

            try {
                var message = new choral.reactive.connection.Message(request);
                serverEvents.messageReceived(message);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

}
