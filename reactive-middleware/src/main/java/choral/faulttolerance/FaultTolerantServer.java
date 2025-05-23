package choral.faulttolerance;

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
    protected final FaultSessionEvent newFaultSessionEvent;
    protected final FaultDataStore dataStore;

    public FaultTolerantServer(FaultDataStore dataStore, Connection rmqCon, String serviceName, OpenTelemetry telemetry, FaultSessionEvent newSessionEvent) throws IOException, TimeoutException {
        super(serviceName, null, RMQChannelSender.factory(rmqCon), telemetry, Duration.ofMinutes(10), null);
        this.connectionManager = new RMQChannelReceiver(rmqCon, serviceName, this);
        this.newFaultSessionEvent = newSessionEvent;
        this.dataStore = dataStore;
    }

    public FaultTolerantServer(FaultDataStore dataStore, Connection rmqCon, String serviceName, FaultSessionEvent newSessionEvent) throws IOException, TimeoutException {
        this(dataStore, rmqCon, serviceName, OpenTelemetry.noop(), newSessionEvent);
    }

    public RMQChannelReceiver connectionManager() {
        return (RMQChannelReceiver) this.connectionManager;
    }

    @Override
    public void close() throws Exception {
        super.close();
        dataStore.close();
    }

    @Override
    protected void startNewSession(TelemetrySession telemetrySession) throws Exception {
        var sessionID = telemetrySession.session.sessionID();

        try {
            super.startNewSession(telemetrySession);
        } catch (Exception e) {
            synchronized (pendingMessages) {
                var messages = pendingMessages.getOrDefault(sessionID, new ArrayList<>());
                for (var message : messages) {
                    message.nack();
                }
                pendingMessages.remove(sessionID);
                telemetrySession.log("Error occurred, rolled back " + messages.size() + " messages");
            }
            throw e;
        }

        synchronized (pendingMessages) {
            var messages = pendingMessages.getOrDefault(sessionID, new ArrayList<>());
            for (var message : messages) {
                message.ack();
            }
            pendingMessages.remove(sessionID);
            telemetrySession.log("Choreography completed, ACKed " + messages.size() + " messages");
        }
    }

    @Override
    protected void runNewSessionEvent(TelemetrySession telemetrySession) throws Exception {
        try (FaultSessionContext sessionCtx = new FaultSessionContext(this, telemetrySession)) {
            newFaultSessionEvent.onNewSession(sessionCtx);
        }
        dataStore.completeSession(telemetrySession.session);
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

    @Override
    public void messageReceived(Message msg) {
        if (dataStore.hasSessionCompleted(msg.session)) {
            logger.info("Received message with completed session: " + msg);
            return;
        }

        super.messageReceived(msg);
    }

    @Override
    public String toString() {
        return "FaultTolerantServer [serviceName=" + serviceName + "]";
    }

    public interface FaultSessionEvent {
        void onNewSession(FaultSessionContext ctx) throws Exception;
    }
}
