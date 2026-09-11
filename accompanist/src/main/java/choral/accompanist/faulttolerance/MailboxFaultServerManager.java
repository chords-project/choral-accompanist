package choral.accompanist.faulttolerance;

import choral.accompanist.connection.Message;
import choral.accompanist.tracing.Logger;
import choral.accompanist.tracing.TelemetrySession;
import choral_reactive.ChannelGrpc;
import choral_reactive.ChannelOuterClass;
import com.google.protobuf.Empty;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.URI;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

public class MailboxFaultServerManager implements FaultServerConnectionManager {

    private final FaultServerConnectionManager.ServerEvents serverEvents;
    private final SQLMailbox mailbox;
    private final String serviceName;
    private final String[] broadcastClients;
    private Server server;
    private final Logger logger;
    private final OpenTelemetry telemetry;
    private final MailboxRecoveryCoordinator coordinator;

    public MailboxFaultServerManager(SQLMailbox mailbox, String serviceName, FaultServerConnectionManager.ServerEvents serverEvents, OpenTelemetry telemetry, String[] broadcastClients) {
        this.broadcastClients = broadcastClients;
        this.mailbox = mailbox;
        this.serviceName = serviceName;
        this.serverEvents = serverEvents;
        this.logger = new Logger(telemetry, MailboxFaultServerManager.class.getName());
        this.telemetry = telemetry;
        this.coordinator = MailboxRecoveryCoordinator.shared(mailbox);
    }

    public static FaultServerConnectionManager.Factory factory(DataSource db, String[] broadcastClients) throws SQLException {
        SQLMailbox mailbox = new SQLMailbox(db);
        var coordinator = MailboxRecoveryCoordinator.shared(mailbox);
        return new FaultServerConnectionManager.Factory() {
            @Override
            public FaultServerConnectionManager makeConnectionManager(String serviceName, FaultServerConnectionManager.ServerEvents events, OpenTelemetry telemetry) {
                return new MailboxFaultServerManager(mailbox, serviceName, events, telemetry, broadcastClients);
            }

            @Override
            public MailboxRecoveryCoordinator recoveryCoordinator() {
                return coordinator;
            }
        };
    }

    @Override
    public void listen(String address) throws Exception {
        logger.info("Starting gRPC server on " + address);

        URI uri = new URI(null, address, null, null, null).parseServerAuthority();
        InetSocketAddress addr = new InetSocketAddress(uri.getHost(), uri.getPort());

        HealthStatusManager health = new HealthStatusManager();

        var serverBuilder = Grpc.newServerBuilderForPort(addr.getPort(), InsecureServerCredentials.create())
                .addService(new MailboxFaultServerManager.ChannelGrpcImpl())
                .addService(health.getHealthService());

        server = serverBuilder.build().start();
        coordinator.start();
        coordinator.announceReady(serviceName, address, broadcastClients);

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

    @Override
    public void recoverableSessionFailure(TelemetrySession telemetrySession) throws Exception {

    }

    @Override
    public void broadcastSessionFailure(TelemetrySession telemetrySession) throws Exception {
        var clientFactory = MailboxFaultClientManager.factory(mailbox.db);

        var clientEvents = new FaultClientConnectionManager.ClientEvents() {
            public void messageDeliveryConfirmed(Message message) {
            }

            public void messageDeliveryFailed(Message message) {
            }
        };

        var failureMessage = new Message(telemetrySession.session, new FailureMarker(), -1);
        telemetrySession.injectSessionContext(failureMessage);

        for (var clientAddress : broadcastClients) {
            var client = clientFactory.makeConnectionManager(clientAddress, clientEvents, telemetry).makeConnection();

            client.sendMessage(failureMessage);
        }
    }

    @Override
    public void sessionCompleted(TelemetrySession telemetrySession) {
    }

    private class ChannelGrpcImpl extends ChannelGrpc.ChannelImplBase {

        @Override
        public void notifyReady(ChannelOuterClass.ReadyNotification request, StreamObserver<Empty> responseObserver) {
            if (request.getAddress().isBlank()) {
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                        .withDescription("ready notification address must not be empty")
                        .asRuntimeException());
                return;
            }
            logger.info("Peer ready: " + request.getServiceName() + " at " + request.getAddress());
            coordinator.destinationReady(request.getAddress());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void sendMessage(ChannelOuterClass.Message request, StreamObserver<Empty> responseObserver) {
            Message message = null;
            String failureDescription = "Failed to deserialize incoming mailbox message";
            try {
                message = new Message(request);
                if (message.message instanceof FailureMarker) {
                    failureDescription = "Failed to handle incoming session failure";
                    handleSessionFailure(message);
                } else {
                    failureDescription = "Failed to persist incoming mailbox message";
                    mailbox.didReceiveMessage(message);
                    failureDescription = "Failed to process incoming mailbox message";
                    serverEvents.messageReceived(message);
                    coordinator.wake();
                }
            } catch (Exception e) {
                TelemetrySession failureSession = message == null
                        ? TelemetrySession.fromGrpcEnvelope(telemetry, request)
                        : new TelemetrySession(telemetry, message);
                try (failureSession) {
                    failureSession.recordException(failureDescription, e, true,
                            Attributes.builder().put("messaging.sequence_number", request.getSequenceNumber()).build());
                    fail(responseObserver, e);
                }
                return;
            }

            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }

        private void handleSessionFailure(Message message) throws Exception {
            try (var telemetrySession = new TelemetrySession(telemetry, message)) {
                var span = telemetrySession.getChoreographySpan();
                try (var ignored = span.makeCurrent()) {
                    serverEvents.sessionFailed(telemetrySession);
                }
            }
        }

        private void fail(StreamObserver<Empty> responseObserver, Exception error) {
            responseObserver.onError(error);
        }
    }

    public static class FailureMarker implements Serializable {
        public FailureMarker() {
        }
    }
}
