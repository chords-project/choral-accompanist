package choral.reactive.connection;

import io.opentelemetry.api.OpenTelemetry;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQException;

import java.io.IOException;
import java.net.URISyntaxException;

public class ZMQServerManager implements ServerConnectionManager {

    private final ZContext context;
    private final ZMQ.Socket receiver;
    private final ServerEvents events;

    public ZMQServerManager(ServerEvents events, OpenTelemetry telemetry) {
        this.context = new ZContext();
        this.receiver = context.createSocket(SocketType.PULL);
        this.events = events;
    }

    @Override
    public void listen(String address) throws URISyntaxException, IOException {
        receiver.bind("tcp://" + address);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                byte[] data = receiver.recv();
                Message msg = Message.deserialize(data);
                this.events.messageReceived(msg);
            } catch (ZMQException e) {
                e.printStackTrace();
                throw e;
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void close() throws IOException {
        this.context.close();
    }
}
