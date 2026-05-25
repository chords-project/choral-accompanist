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
    private final String[] broadcastClients;
    private Server server;
    private final Logger logger;
    private final OpenTelemetry telemetry;

    public MailboxFaultServerManager(SQLMailbox mailbox, String serviceName, FaultServerConnectionManager.ServerEvents serverEvents, OpenTelemetry telemetry, String[] broadcastClients) {
        this.broadcastClients = broadcastClients;
        this.mailbox = mailbox;
        this.serverEvents = serverEvents;
        this.logger = new Logger(telemetry, MailboxFaultServerManager.class.getName());
        this.telemetry = telemetry;
    }

    public static FaultServerConnectionManager.Factory factory(DataSource db, String[] broadcastClients) throws SQLException {
        SQLMailbox mailbox = new SQLMailbox(db);
        return (String serviceName, FaultServerConnectionManager.ServerEvents events, OpenTelemetry telemetry) ->
                new MailboxFaultServerManager(mailbox, serviceName, events, telemetry, broadcastClients);
    }

    @Override
    public void listen(String address) throws Exception {
        this.recoverReceivedMessages();

        logger.info("Starting gRPC server on " + address);

        URI uri = new URI(null, address, null, null, null).parseServerAuthority();
        InetSocketAddress addr = new InetSocketAddress(uri.getHost(), uri.getPort());

        HealthStatusManager health = new HealthStatusManager();

        var serverBuilder = Grpc.newServerBuilderForPort(addr.getPort(), InsecureServerCredentials.create())
                .addService(new MailboxFaultServerManager.ChannelGrpcImpl())
                .addService(health.getHealthService());

        server = serverBuilder.build().start();

        try {
            server.awaitTermination();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void recoverReceivedMessages() throws Exception {
        var msgs = this.mailbox.recoverReceivedMessages();
        logger.info("Recovered " + msgs.size() + " messages");
        for (var msg : msgs) {
            this.serverEvents.messageReceived(msg);
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
        public void sendMessage(ChannelOuterClass.Message request, StreamObserver<Empty> responseObserver) {
            logger.debug("Received message on gRPC server");

            try {
                var message = new Message(request);

                if (message.message instanceof FailureMarker) {
                    serverEvents.sessionFailed(message.session);
                } else {
                    mailbox.didReceiveMessage(message);
                    serverEvents.messageReceived(message);
                }
            } catch (Exception e) {
                responseObserver.onError(e);
                throw new RuntimeException(e);
            }

            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    public static class FailureMarker implements Serializable {
        public FailureMarker() {
        }
    }
}
