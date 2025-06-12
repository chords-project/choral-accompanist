package choral.faulttolerance;

import choral.reactive.Session;
import choral.reactive.connection.Message;
import choral.reactive.connection.ServerConnectionManager;
import com.rabbitmq.client.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class RMQChannelReceiver implements ServerConnectionManager {

    final String queueName;
    final RMQReceiverEvents events;
    final Connection connection;
    Channel channel;

    public RMQChannelReceiver(Connection connection, String serviceName, RMQReceiverEvents events) throws IOException, TimeoutException {
        this.connection = connection;
        this.queueName = serviceName;
        this.events = events;
    }

    @Override
    public void listen(String address) throws IOException, TimeoutException {
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

    public void broadcastSessionFailure(Integer sessionID) throws IOException, InterruptedException, TimeoutException {
        byte[] body = sessionID.toString().getBytes();
        channel.basicPublish("faults", "", null, body);
    }

    @Override
    public void close() throws IOException, TimeoutException {
        channel.close();
    }

    protected class MessageDeliverCallback implements DeliverCallback {
        @Override
        public void handle(String consumerTag, Delivery message) throws IOException {
            try {
                Message msg = Message.deserialize(message.getBody());
                events.messageReceived(msg);
                events.messageToAck(new MessageAck(message.getEnvelope().getDeliveryTag(), msg.session.sessionID()));
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    protected class FaultDeliverCallback implements DeliverCallback {
        @Override
        public void handle(String consumerTag, Delivery message) throws IOException {
            int sessionID = Integer.parseInt(new String(message.getBody()));
            var ack = new MessageAck(message.getEnvelope().getDeliveryTag(), sessionID);
            events.sessionFailed(sessionID, ack);
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

    public interface RMQReceiverEvents extends ServerEvents {
        void messageToAck(MessageAck messageAck);

        void sessionFailed(int sessionID, MessageAck messageAck) throws IOException;
    }
}
