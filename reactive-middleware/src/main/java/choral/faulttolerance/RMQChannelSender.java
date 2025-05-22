package choral.faulttolerance;

import choral.reactive.connection.ClientConnectionManager;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import choral.reactive.connection.Message;
import com.rabbitmq.client.Channel;
import io.opentelemetry.api.OpenTelemetry;

public class RMQChannelSender implements ClientConnectionManager {

    final Channel channel;
    final String queueName;

    public RMQChannelSender(com.rabbitmq.client.Connection connection, String queueName) throws IOException {
        this.queueName = queueName;
        channel = connection.createChannel();
        channel.confirmSelect();
        channel.queueDeclare(queueName, true, false, false, null);
    }

    public static ClientConnectionManager.Factory factory(com.rabbitmq.client.Connection connection) {
        return (address, telemetry) -> new RMQChannelSender(connection, address);
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
            channel.waitForConfirmsOrDie(5_000);
        }

        @Override
        public void close() throws Exception {

        }
    }
}
