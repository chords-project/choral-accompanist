package choral.accompanist.faulttolerance;

import choral.accompanist.connection.Message;
import choral.accompanist.tracing.JaegerConfiguration;
import choral.accompanist.tracing.Logger;
import choral_reactive.ChannelGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MailboxFaultClientManager implements FaultClientConnectionManager {
    private final ManagedChannel channel;
    private final ChannelGrpc.ChannelFutureStub futureStub;
    private final OpenTelemetry telemetry;
    private final Logger logger;
    private final String address;
    private final SQLMailbox mailbox;
    private final ClientEvents events;

    public MailboxFaultClientManager(SQLMailbox mailbox, String address, ClientEvents events, OpenTelemetry telemetry) throws URISyntaxException, SQLException {
        this.mailbox = mailbox;
        this.address = address;
        this.telemetry = telemetry;
        this.logger = new Logger(telemetry, MailboxFaultClientManager.class.getName());
        this.events = events;

        URI uri = new URI(null, address, null, null, null).parseServerAuthority();
        InetSocketAddress socketAddr = new InetSocketAddress(uri.getHost(), uri.getPort());

        this.channel = ManagedChannelBuilder
                .forAddress(socketAddr.getHostString(), socketAddr.getPort())
                .usePlaintext()
                .build();

        this.futureStub = ChannelGrpc
                .newFutureStub(channel);
    }

    public static FaultClientConnectionManager.Factory factory(DataSource db) throws SQLException {
        SQLMailbox mailbox = new SQLMailbox(db);
        return (String address, ClientEvents events, OpenTelemetry telemetry) -> new MailboxFaultClientManager(mailbox, address, events, telemetry);
    }

    @Override
    public Connection makeConnection() {
        logger.debug("Connect to gRPC server " + address);
        return new MailboxFaultClientManager.ClientConnection();
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

            boolean alreadySent = mailbox.aboutToSendMessage(msg);
            if (alreadySent) {
                logger.info("Message already sent");
                return;
            }

            var result = futureStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .sendMessage(msg.toGrpcMessage());

            Attributes attributes = Attributes.builder()
                    .put("message", msg.toString())
                    .put("address", address)
                    .build();

            long startTime = System.nanoTime();

            result.addListener(() -> {
                try {
                    result.get();

                    // Mark message as acknowledged in database
                    events.messageDeliveryConfirmed(msg);
                    mailbox.didDeliverMessage(msg);

                    double duration = (System.nanoTime() - startTime) / 1_000_000.0;

                    connectionSpan.addEvent("Message sent to " + address + " (" + (long) duration + " ms)", attributes);
                } catch (Exception e) {
                    logger.exception("failed to send message to " + address, e);
                    connectionSpan.setAttribute("error", true);
                    connectionSpan.recordException(e);
                    events.messageDeliveryFailed(msg);
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
