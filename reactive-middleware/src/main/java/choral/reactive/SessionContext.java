package choral.reactive;

import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.connection.ClientConnectionsStore;
import choral.reactive.tracing.TelemetrySession;
import io.opentelemetry.api.common.Attributes;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashSet;

/**
 * A context object for creating channels and logging telemetry events in a particular session.
 */
public class SessionContext implements AutoCloseable {

    private final ReactiveServer server;
    private final ClientConnectionsStore clientStore;
    public final Session session;
    private final TelemetrySession telemetrySession;
    private final HashSet<AutoCloseable> closeHandles = new HashSet<>();

    public SessionContext(ReactiveServer server, Session session, TelemetrySession telemetrySession) {
        this.server = server;
        this.clientStore = server.getClientStore();
        this.session = session;
        this.telemetrySession = telemetrySession;
    }

    /**
     * Creates a channel for receiving messages from the given client in this session.
     *
     * @param clientService the name of the client service
     */
    public ReactiveChannel_B<Serializable> chanB(String clientService) {
        Session newSession = session.replacingSender(clientService);
        return new ReactiveChannel_B<>(newSession, server, telemetrySession);
    }

    /**
     * Creates a channel for sending messages to the given client in this session.
     *
     * @param connectionManager an implementation of the communication middleware
     */
    public ReactiveChannel_A<Serializable> chanA(ClientConnectionManager connectionManager)
            throws Exception {
        ReactiveClient client = new ReactiveClient(connectionManager, server.serviceName, telemetrySession);
        closeHandles.add(client);
        return client.chanA(session);
    }

    /**
     * Creates a channel for sending messages to the given client in this session.
     *
     * @param clientAddressEnv the name of the environment variable containing the address of the client
     */
    public ReactiveChannel_A<Serializable> chanA(String clientAddressEnv)
            throws Exception {
        var connectionManager = this.clientStore.fromEnv(clientAddressEnv);
        return chanA(connectionManager);
    }

    /**
     * Creates a bidirectional channel between this service and the given client.
     *
     * @param clientService     the name of the service to which we are connecting
     * @param connectionManager an implementation of the communication middleware
     */
    public ReactiveSymChannel<Serializable> symChan(String clientService,
                                                    ClientConnectionManager connectionManager)
            throws Exception {
        var a = chanA(connectionManager);
        var b = chanB(clientService);
        return new ReactiveSymChannel<>(a, b);
    }

    /**
     * Creates a bidirectional channel between this service and the given client.
     *
     * @param clientService    the name of the service to which we are connecting
     * @param clientAddressEnv the name of the environment variable containing the address of the client
     */
    public ReactiveSymChannel<Serializable> symChan(String clientService,
                                                    String clientAddressEnv)
            throws Exception {
        var a = chanA(clientAddressEnv);
        var b = chanB(clientService);
        return new ReactiveSymChannel<>(a, b);
    }

    public void log(String message) {
        telemetrySession.log(message);
    }

    public void log(String message, Attributes attributes) {
        telemetrySession.log(message, attributes);
    }

    @Override
    public void close() throws Exception {
        for (AutoCloseable handle : closeHandles) {
            handle.close();
        }
    }
}
