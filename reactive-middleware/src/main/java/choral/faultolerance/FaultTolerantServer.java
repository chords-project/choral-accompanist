package choral.faultolerance;

import choral.reactive.ReactiveServer;
import choral.reactive.Session;
import choral.reactive.connection.Message;
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

    public FaultTolerantServer(Connection connection, String serviceName, OpenTelemetry telemetry, NewSessionEvent newSessionEvent) throws IOException, TimeoutException {
        super(serviceName, null, telemetry, Duration.ofMinutes(10), newSessionEvent);
        this.connectionManager = new RMQChannelReceiver(connection, serviceName, this);
    }

    public FaultTolerantServer(Connection connection, String serviceName, NewSessionEvent newSessionEvent) throws IOException, TimeoutException {
        this(connection, serviceName, OpenTelemetry.noop(), newSessionEvent);
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
    public void messageToAck(RMQChannelReceiver.MessageAck messageAck) {
        synchronized (pendingMessages) {
            pendingMessages.merge(messageAck.sessionID, new ArrayList<>(List.of(messageAck)), (a, b) -> {
                a.addAll(b);
                return a;
            });
        }
    }
}
