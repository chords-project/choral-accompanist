package choral.reactive.connection;

import io.opentelemetry.api.OpenTelemetry;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.TimeoutException;

/**
 * An object that contains logic for connecting to a remote server. This interface abstracts
 * details of the connection protocol, such as TCP or gRPC, and whether the connection is pooled.
 */
public interface ClientConnectionManager extends AutoCloseable {
    Connection makeConnection() throws IOException, InterruptedException;

    @Override
    void close() throws IOException, InterruptedException;

    public interface Connection extends AutoCloseable {
        void sendMessage(Message msg) throws Exception;

        @Override
        void close() throws IOException, InterruptedException;
    }

    public static ClientConnectionManager makeConnectionManager(String address, OpenTelemetry telemetry)
            throws URISyntaxException, IOException {
        // return new TCPClientManagerSimple(address, telemetry);
        // return new TCPClientManagerPool(address, telemetry);
        return new GRPCClientManager(address, telemetry);
        // return new GRPCStreamingClientManager(address, telemetry);
    }
}
