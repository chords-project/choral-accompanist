package choral.reactive.connection;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.io.IOException;
import java.net.URISyntaxException;

public interface ServerConnectionManager extends AutoCloseable {
    void listen(String address) throws Exception;

    @Override
    void close() throws Exception;

    static ServerConnectionManager makeConnectionManager(ServerEvents events, OpenTelemetry telemetry) {
        return new GRPCServerManager(events, telemetry);
    }

    interface ServerEvents {
        /**
         * A callback executed whenever the server receives a message.
         */
        void messageReceived(Message message);
    }
}
