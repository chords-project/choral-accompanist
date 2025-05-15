package choral.faultolerance;

import choral.reactive.connection.ClientConnectionManager;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import choral.reactive.connection.Message;
import com.rabbitmq.client.Channel;

public class RMQChannelSender implements ClientConnectionManager {

    final Channel channel;
    final String queueName;

    public RMQChannelSender(com.rabbitmq.client.Connection connection, String queueName) throws IOException, TimeoutException {
        this.queueName = queueName;
        channel = connection.createChannel();
        channel.queueDeclare(queueName, true, false, false, null);
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
        }

        @Override
        public void close() throws Exception {

        }
    }
}
