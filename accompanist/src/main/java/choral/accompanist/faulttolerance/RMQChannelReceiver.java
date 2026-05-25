package choral.accompanist.faulttolerance;

import choral.accompanist.Session;
import choral.accompanist.connection.Message;
import choral.accompanist.tracing.TelemetrySession;
import com.rabbitmq.client.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class RMQChannelReceiver implements FaultServerConnectionManager {

    final String queueName;
    final ServerEvents events;
    Connection connection;
    Channel channel;
    private final HashMap<Integer, ArrayList<MessageAck>> pendingMessages = new HashMap<>();

    public RMQChannelReceiver(String serviceName, ServerEvents events) {
        this.queueName = serviceName;
        this.events = events;
    }

    public static FaultServerConnectionManager.Factory factory() {
        return (serviceName, events, telemetry) -> new RMQChannelReceiver(serviceName, events);
    }

    @Override
    public void listen(String address) throws IOException, TimeoutException {
        var connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(address);

        this.connection = connectionFactory.newConnection();
        this.channel = connection.createChannel();

        // Message receive queue
        channel.queueDeclare(queueName, true, false, false, null);
        channel.basicConsume(queueName, false, new MessageDeliverCallback(), (CancelCallback) null);

        // Fault fanout exchange
        channel.exchangeDeclare("faults", BuiltinExchangeType.FANOUT);

        // Fault notification receive queue
        String faultQueueName = queueName + "-faults";
        channel.queueDeclare(faultQueueName, true, false, false, null);
        channel.basicConsume(faultQueueName, false, new FaultDeliverCallback(), (CancelCallback) null);
        channel.queueBind(faultQueueName, "faults", "");
    }

    @Override
    public void close() throws IOException, TimeoutException {
        channel.close();
        connection.close();
    }

    @Override
    public void recoverableSessionFailure(TelemetrySession telemetrySession) throws IOException {
        var sessionID = telemetrySession.session.sessionID();

        synchronized (pendingMessages) {
            var messages = pendingMessages.getOrDefault(sessionID, new ArrayList<>());
            for (var message : messages) {
                message.nack();
            }
            pendingMessages.remove(sessionID);
            telemetrySession.log("Error occurred, rolled back " + messages.size() + " messages");
        }
    }

    @Override
    public void broadcastSessionFailure(TelemetrySession telemetrySession) throws IOException {
        var sessionID = telemetrySession.session.sessionID();
        var choreography = telemetrySession.session.choreographyName();
        byte[] body = (sessionID.toString() + "$" + choreography).getBytes();
        channel.basicPublish("faults", "", null, body);
    }

    @Override
    public void sessionCompleted(TelemetrySession telemetrySession) throws IOException {
        var sessionID = telemetrySession.session.sessionID();

        synchronized (pendingMessages) {
            var messages = pendingMessages.getOrDefault(sessionID, new ArrayList<>());
            for (var message : messages) {
                message.ack();
            }
            pendingMessages.remove(sessionID);
            telemetrySession.log("Choreography completed, ACKed " + messages.size() + " messages");
        }
    }

    protected void messageToAck(RMQChannelReceiver.MessageAck messageAck) {
        synchronized (pendingMessages) {
            pendingMessages.merge(messageAck.sessionID, new ArrayList<>(List.of(messageAck)), (a, b) -> {
                a.addAll(b);
                return a;
            });
        }
    }

    protected class MessageDeliverCallback implements DeliverCallback {
        @Override
        public void handle(String consumerTag, Delivery message) throws IOException {
            try {
                Message msg = Message.deserialize(message.getBody());
                events.messageReceived(msg);
                messageToAck(new MessageAck(message.getEnvelope().getDeliveryTag(), msg.session.sessionID()));
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    protected class FaultDeliverCallback implements DeliverCallback {
        @Override
        public void handle(String consumerTag, Delivery message) throws IOException {
            var body = new String(message.getBody()).split("\\$", 2);
            int sessionID = Integer.parseInt(body[0]);
            var choreography = body[1];
            var ack = new MessageAck(message.getEnvelope().getDeliveryTag(), sessionID);
            try {
                events.sessionFailed(new Session(choreography, "UNKNOWN_SENDER", sessionID));
            } catch (Exception e) {
                ack.nack();
                throw new RuntimeException(e);
            }
            ack.ack();
        }
    }

    public class MessageAck {
        public final long deliveryTag;
        public final int sessionID;

        public MessageAck(long deliveryTag, int sessionID) {
            this.deliveryTag = deliveryTag;
            this.sessionID = sessionID;
        }

        public void ack() throws IOException {
            channel.basicAck(deliveryTag, false);
        }

        public void nack() throws IOException {
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
