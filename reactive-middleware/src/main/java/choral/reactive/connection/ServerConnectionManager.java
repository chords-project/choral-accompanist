package choral.reactive.connection;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.io.IOException;
import java.net.URISyntaxException;

public interface ServerConnectionManager extends AutoCloseable {
    public void listen(String address) throws URISyntaxException, IOException;

    @Override
    public void close() throws IOException;

    public static ServerConnectionManager makeConnectionManager(ServerEvents events, OpenTelemetry telemetry) {
        // return new TCPServerManagerSimple(events, telemetry);
        // return new TCPServerManagerNio(events, telemetry);
        return new GRPCServerManager(events, telemetry);
        // return new GRPCStreamingServerManager(events, telemetry);
    }

    public interface ServerEvents {
        /** A callback executed whenever the server receives a message. */
        public void messageReceived(Message message);
    }
}
