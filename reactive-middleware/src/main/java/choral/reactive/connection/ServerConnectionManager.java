package choral.reactive.connection;

import io.opentelemetry.api.OpenTelemetry;

public interface ServerConnectionManager extends AutoCloseable {
    void listen(String address) throws Exception;

    @Override
    void close() throws Exception;

    interface ServerEvents {
        /**
         * A callback executed whenever the server receives a message.
         */
        void messageReceived(Message message);
    }

    static ServerConnectionManager.Factory defaultFactory() {
        return GRPCServerManager::new;
    }

    interface Factory {
        ServerConnectionManager makeConnectionManager(String serviceName, ServerEvents events, OpenTelemetry telemetry);
    }
}
