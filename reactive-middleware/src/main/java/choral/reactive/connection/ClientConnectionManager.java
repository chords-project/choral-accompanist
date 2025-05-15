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
    Connection makeConnection() throws Exception;

    @Override
    void close() throws Exception;

    interface Connection extends AutoCloseable {
        void sendMessage(Message msg) throws Exception;

        @Override
        void close() throws Exception;
    }

    static ClientConnectionManager makeConnectionManager(String address, OpenTelemetry telemetry)
            throws URISyntaxException, IOException {
        return new GRPCClientManager(address, telemetry);
    }
}
