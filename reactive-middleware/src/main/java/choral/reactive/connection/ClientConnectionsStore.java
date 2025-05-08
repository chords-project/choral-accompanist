package choral.reactive.connection;

import io.opentelemetry.api.OpenTelemetry;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;

/**
 * An object that stores client connections and simplifies the process of obtaining new connections.
 */
public class ClientConnectionsStore {

    private HashMap<String, ClientConnectionManager> connections;
    private OpenTelemetry telemetry;

    public ClientConnectionsStore(OpenTelemetry telemetry) {
        this.connections = new HashMap<>();
        this.telemetry = telemetry;
    }

    public ClientConnectionManager fromEnv(String envVar) {
        String address = System.getenv(envVar);
        if (address == null) {
            throw new RuntimeException("Environment variable was not found: " + envVar);
        }

        return fromAddress(address);
    }

    /**
     * Obtain a client connection to a server with the given address.
     * The client connection will be caches and reused for the same address.
     *
     * @param address the address of the server to connect to
     * @return a client connection connected to the server
     */
    public ClientConnectionManager fromAddress(String address) {
        if (connections.containsKey(address)) {
            return connections.get(address);
        }

        try {
            var conn = ClientConnectionManager.makeConnectionManager(address, telemetry);
            connections.put(address, conn);
            return conn;
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
