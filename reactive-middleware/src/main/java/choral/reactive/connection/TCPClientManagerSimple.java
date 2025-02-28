package choral.reactive.connection;

import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.Logger;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;

/**
 * This object contains logic for connecting to a remote server using TCP. Not thread-safe.
 *
 * @see TCPClientManagerPool
 */
public class TCPClientManagerSimple implements ClientConnectionManager {

    public final String address;
    private final InetSocketAddress socketAddr;
    private final OpenTelemetry telemetry;
    private final Logger logger;

    public TCPClientManagerSimple(String address, OpenTelemetry telemetry) throws URISyntaxException {
        this.address = address;
        this.telemetry = telemetry;
        this.logger = new Logger(telemetry, TCPClientManagerSimple.class.getName());

        URI uri = new URI(null, address, null, null, null).parseServerAuthority();
        this.socketAddr = new InetSocketAddress(uri.getHost(), uri.getPort());
    }

    @Override
    public ClientConnection makeConnection() throws IOException {
        return new ClientConnection();
    }

    private class ClientConnection implements ClientConnectionManager.Connection {

        private Socket connection = null;
        private ByteArrayOutputStream objectBuffer = new ByteArrayOutputStream(4096);
        private Tracer tracer;

        protected ClientConnection() throws IOException {
            this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);

            Span span = tracer.spanBuilder("ClientConnection connect").setAttribute("connection.address", address)
                    .startSpan();

            this.connection = new Socket(socketAddr.getHostName(), socketAddr.getPort());

            span.end();
        }

        @Override
        public void sendMessage(Message msg) throws IOException {
            objectBuffer.reset();
            ObjectOutputStream objectStream = new ObjectOutputStream(objectBuffer);
            objectStream.writeObject(msg);
            objectStream.close();

            ByteBuffer sendBuffer = ByteBuffer.allocate(objectBuffer.size() + 4)
                    .putInt(objectBuffer.size())
                    .put(objectBuffer.toByteArray());

            logger.info("Sending object of size: " + objectBuffer.size());

            this.connection.getOutputStream().write(sendBuffer.array());
        }

        @Override
        public void close() throws IOException {
            logger.info("Closing client connection");

            // Send quit event
            connection.getOutputStream().write(ByteBuffer.allocate(4).putInt(-1).array());

            connection.close();
        }

        @Override
        public String toString() {
            return "ClientConnection [address=" + address + "]";
        }
    }

    @Override
    public void close() throws IOException {
    }
}
