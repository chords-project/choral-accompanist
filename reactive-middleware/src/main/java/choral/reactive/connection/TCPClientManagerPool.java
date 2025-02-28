package choral.reactive.connection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

/**
 * This object contains logic for connecting to a remote server using TCP. Thread-safe. Does not
 * reconnect if the connection is lost.
 */
public class TCPClientManagerPool implements ClientConnectionManager {

    public final String address;
    private final InetSocketAddress socketAddr;
    private SocketChannel channel = null;
    private ByteArrayOutputStream objectBuffer = new ByteArrayOutputStream(4096);
    private CountDownLatch connectedLatch = new CountDownLatch(1);
    private final OpenTelemetry telemetry;
    private final Logger logger;
    private final Span poolSpan;

    public TCPClientManagerPool(String address, OpenTelemetry telemetry)
            throws URISyntaxException, UnknownHostException, IOException {
        this.address = address;
        this.telemetry = telemetry;
        this.logger = new Logger(telemetry, TCPClientManagerPool.class.getName());

        this.poolSpan = telemetry.getTracer(JaegerConfiguration.TRACER_NAME)
                .spanBuilder("TCPClientManagerPool pool")
                .setAttribute("channel.address", address)
                .startSpan();

        URI uri = new URI(null, address, null, null, null).parseServerAuthority();
        this.socketAddr = new InetSocketAddress(uri.getHost(), uri.getPort());
        connect();
    }

    public void connect() {
        Thread.ofVirtual()
                .start(() -> {
                    for (int i = 0; i < 10; i++) {
                        try {
                            synchronized (TCPClientManagerPool.this) {
                                this.channel = SocketChannel.open(socketAddr);
                                this.channel.socket().setKeepAlive(true);
                            }

                            poolSpan.addEvent("connected to server");

                            logger.info(
                                    "TCPReactiveClientConnection successfully connected to server: " + address);
                            break;
                        } catch (IOException e) {
                            Duration waitTime = Duration.ofMillis((long) Math.pow(2, i) * 1000L);

                            poolSpan.addEvent("failed to connect to server",
                                    Attributes.builder()
                                            .put("retry.count", i + 1)
                                            .put("retry.waitTime", waitTime.toMillis())
                                            .build());

                            logger.info(
                                    "TCPReactiveClientConnection failed to connect to client: attempt=" +
                                            (i + 1) +
                                            ", address=" +
                                            address +
                                            ", retryWait=" +
                                            waitTime.toMillis() +
                                            "ms");
                            try {
                                Thread.sleep(waitTime);
                            } catch (InterruptedException e1) {
                                e1.printStackTrace();
                                break;
                            }
                        }
                    }

                    this.connectedLatch.countDown();
                });
    }

    @Override
    public void close() throws IOException {
        logger.info("Closing client manager pool");
        poolSpan.addEvent("Closing client manager pool");

        try {
            // Send quit event
            channel.write(ByteBuffer.allocate(4).putInt(-1).flip());
            channel.close();
        } catch (IOException e) {
            poolSpan.setAttribute("error", true);
            poolSpan.recordException(e);
        } finally {
            poolSpan.end();
        }

    }

    @Override
    public ClientConnection makeConnection() {
        return new ClientConnection();
    }

    public class ClientConnection implements ClientConnectionManager.Connection {
        private final Span connectionSpan;

        public ClientConnection() {
            this.connectionSpan = telemetry.getTracer(JaegerConfiguration.TRACER_NAME)
                    .spanBuilder("TCPClientManagerPool connection")
                    .setParent(poolSpan.storeInContext(Context.current()))
                    .startSpan();
        }

        @Override
        public void sendMessage(Message msg) throws IOException, InterruptedException {

            Span sendMessageSpan = telemetry.getTracer(JaegerConfiguration.TRACER_NAME)
                    .spanBuilder("TCPClientManagerPool send message")
                    .setParent(connectionSpan.storeInContext(Context.current()))
                    .startSpan();

            try {
                sendMessageSpan.addEvent("send message begin",
                        Attributes.builder()
                                .put("message", msg.toString())
                                .put("channel.address", address)
                                .build());

                connectedLatch.await(10, TimeUnit.SECONDS);
                synchronized (TCPClientManagerPool.this) {
                    objectBuffer.reset();
                    ObjectOutputStream objectStream = new ObjectOutputStream(objectBuffer);
                    objectStream.writeObject(msg);
                    objectStream.close();

                    ByteBuffer sendBuffer = ByteBuffer.allocate(objectBuffer.size() + 4)
                            .putInt(objectBuffer.size())
                            .put(objectBuffer.toByteArray())
                            .flip();

                    sendMessageSpan.addEvent("Sending data: " + sendBuffer);
                    channel.write(sendBuffer);
                }
                sendMessageSpan.addEvent("send message done");

            } catch (IOException | InterruptedException e) {
                sendMessageSpan.setAttribute("error", true);
                sendMessageSpan.recordException(e);

                // Shut down connection
                sendMessageSpan.end();
                this.close();
                TCPClientManagerPool.this.close();

                throw e;
            } finally {
                sendMessageSpan.end();
            }
        }

        @Override
        public void close() {
            connectionSpan.end();
        }
    }
}
