package choral.reactive.connection;

import io.opentelemetry.api.OpenTelemetry;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;

public class ZMQClientManager implements ClientConnectionManager {

    private final ZContext context;
    private final String address;

    public ZMQClientManager(String address, OpenTelemetry telemetry) {
        this.context = new ZContext();
        this.address = address;
    }

    @Override
    public Connection makeConnection() throws IOException, InterruptedException {
        return new ClientConnection();
    }

    @Override
    public void close() throws IOException, InterruptedException {
        this.context.close();
    }

    public class ClientConnection implements Connection {

        private final ZMQ.Socket publisher;

        private ClientConnection() {
            this.publisher = context.createSocket(SocketType.PUB);
            publisher.connect("tcp://" + address);
        }

        @Override
        public void sendMessage(Message msg) throws Exception {
            publisher.send(msg.serialize());
        }

        @Override
        public void close() throws IOException, InterruptedException {
            publisher.close();
        }
    }
}
