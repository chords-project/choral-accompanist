package choral.faulttolerance;

import choral.reactive.ReactiveServer;
import choral.reactive.Session;
import choral.reactive.connection.Message;
import choral.reactive.connection.ZMQClientManager;
import choral.reactive.tracing.TelemetrySession;
import com.rabbitmq.client.Connection;
import io.opentelemetry.api.OpenTelemetry;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class FaultTolerantServer extends ReactiveServer implements RMQChannelReceiver.RMQReceiverEvents {

    private final HashMap<Integer, ArrayList<RMQChannelReceiver.MessageAck>> pendingMessages = new HashMap<>();
    protected final FaultSessionEvent newFaultSessionEvent;

    public FaultTolerantServer(Connection connection, String serviceName, OpenTelemetry telemetry, FaultSessionEvent newSessionEvent) throws IOException, TimeoutException {
        super(serviceName, null, RMQChannelSender.factory(connection), telemetry, Duration.ofMinutes(10), null);
        this.connectionManager = new RMQChannelReceiver(connection, serviceName, this);
        this.newFaultSessionEvent = newSessionEvent;
    }

    public FaultTolerantServer(Connection connection, String serviceName, FaultSessionEvent newSessionEvent) throws IOException, TimeoutException {
        this(connection, serviceName, OpenTelemetry.noop(), newSessionEvent);
    }

    public RMQChannelReceiver connectionManager() {
        return (RMQChannelReceiver) this.connectionManager;
    }

    @Override
    protected void startNewSession(Message msg, TelemetrySession telemetrySession) throws Exception {
        try {
            super.startNewSession(msg, telemetrySession);
        } catch (Exception e) {
            synchronized (pendingMessages) {
                var messages = pendingMessages.getOrDefault(msg.session.sessionID(), new ArrayList<>());
                for (var message : messages) {
                    message.nack();
                }
                pendingMessages.remove(msg.session.sessionID());
                telemetrySession.log("Error occurred, rolled back " + messages.size() + " messages");
            }
            throw e;
        }

        synchronized (pendingMessages) {
            var messages = pendingMessages.getOrDefault(msg.session.sessionID(), new ArrayList<>());
            for (var message : messages) {
                message.ack();
            }
            pendingMessages.remove(msg.session.sessionID());
            telemetrySession.log("Choreography completed, ACKed " + messages.size() + " messages");
        }
    }

    @Override
    protected void runNewSessionEvent(TelemetrySession telemetrySession) throws Exception {
        try (FaultSessionContext sessionCtx = new FaultSessionContext(this, telemetrySession)) {
            newFaultSessionEvent.onNewSession(sessionCtx);
        }
    }

    @Override
    public FaultSessionContext registerSession(Session session, TelemetrySession telemetrySession) {
        logger.debug("Registering session " + session.sessionID());

        synchronized (this) {
            knownSessionIDs.add(session.sessionID());
            telemetrySessionMap.put(session.sessionID(), telemetrySession);
        }

        return new FaultSessionContext(this, telemetrySession);
    }

    @Override
    public void messageToAck(RMQChannelReceiver.MessageAck messageAck) {
        synchronized (pendingMessages) {
            pendingMessages.merge(messageAck.sessionID, new ArrayList<>(List.of(messageAck)), (a, b) -> {
                a.addAll(b);
                return a;
            });
        }
    }

    public interface FaultSessionEvent {
        void onNewSession(FaultSessionContext ctx) throws Exception;
    }
}
