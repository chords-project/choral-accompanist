package choral.faulttolerance;

import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.connection.Message;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class RMQChannelSender implements FaultClientConnectionManager {

    final Channel channel;
    final String queueName;
    final ClientEvents events;

    public RMQChannelSender(com.rabbitmq.client.Connection connection, String queueName, ClientEvents events) throws IOException {
        this.queueName = queueName;
        this.events = events;
        channel = connection.createChannel();
        channel.confirmSelect();
        channel.queueDeclare(queueName, true, false, false, null);
    }

    public static FaultClientConnectionManager.Factory factory(com.rabbitmq.client.Connection connection) {
        return (address, events, telemetry) -> new RMQChannelSender(connection, address, events);
    }

    @Override
    public Connection makeConnection() {
        return new ChannelConnection();
    }

    @Override
    public void close() throws TimeoutException, IOException {
        channel.close();
    }

    public class ChannelConnection implements Connection {
        @Override
        public void sendMessage(Message msg) throws Exception {
            byte[] body = msg.serialize();
            channel.basicPublish("", queueName, null, body);

            channel.addConfirmListener(new ConfirmListener() {
                @Override
                public void handleAck(long deliveryTag, boolean multiple) throws IOException {
                    events.messageDeliveryConfirmed(msg);
                }

                @Override
                public void handleNack(long deliveryTag, boolean multiple) throws IOException {
                    events.messageDeliveryFailed(msg);
                }
            });
        }

        @Override
        public void close() throws Exception {

        }
    }
}
