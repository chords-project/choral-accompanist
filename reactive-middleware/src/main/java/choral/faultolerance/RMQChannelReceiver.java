package choral.faultolerance;

import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.connection.Message;
import choral.reactive.connection.ServerConnectionManager;
import com.rabbitmq.client.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.TimeoutException;

public class RMQChannelReceiver implements ServerConnectionManager, DeliverCallback {

    final String queueName;
    final ServerEvents events;
    final Connection connection;
    Channel channel;

    public RMQChannelReceiver(Connection connection, String queueName, ServerEvents events) throws IOException, TimeoutException {
        this.connection = connection;
        this.queueName = queueName;
        this.events = events;
    }

    @Override
    public void listen(String address) throws IOException, TimeoutException {
        this.channel = connection.createChannel();
        channel.queueDeclare(queueName, true, false, false, null);
        channel.basicConsume(queueName, true, this, consumerTag -> {
        });
    }

    @Override
    public void close() throws IOException, TimeoutException {
        channel.close();
    }

    @Override
    public void handle(String consumerTag, Delivery message) throws IOException {
        try {
            Message msg = Message.deserialize(message.getBody());
            events.messageReceived(msg);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }
}
