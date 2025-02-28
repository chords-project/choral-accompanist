package choral.reactive.connection;

import choral.reactive.tracing.Logger;
import io.opentelemetry.api.OpenTelemetry;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.StreamCorruptedException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;

public class TCPServerManagerSimple implements ServerConnectionManager {

    private ServerSocket serverSocket = null;
    private final OpenTelemetry telemetry;
    private final Logger logger;
    private final ServerEvents events;

    public TCPServerManagerSimple(ServerEvents events, OpenTelemetry telemetry) {
        this.events = events;
        this.telemetry = telemetry;
        this.logger = new Logger(telemetry, TCPServerManagerSimple.class.getName());
    }

    /**
     * Start the server listening on the given address, blocking the thread.
     *
     * @param address the address for the server to listen on. Example
     *                "0.0.0.0:1234"
     * @throws URISyntaxException if the onNewSession event handler has not been
     *                            registered before calling
     */
    @Override
    public void listen(String address) throws URISyntaxException {
        logger.info("TCPReactiveServer listener starting on " + address);

        URI uri = new URI(null, address, null, null, null).parseServerAuthority();
        InetSocketAddress addr = new InetSocketAddress(uri.getHost(), uri.getPort());

        try {
            serverSocket = new ServerSocket(addr.getPort(), 50, addr.getAddress());
            logger.info("TCPReactiveServer listening successfully on " + serverSocket.getLocalSocketAddress());

            while (true) {
                Socket connection = serverSocket.accept();
                Thread.ofVirtual()
                        .name("CLIENT_CONNECTION_" + connection)
                        .start(() -> {
                            clientListen(connection);
                        });
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void clientListen(Socket connection) {
        try {
            logger.info("TCPReactiveServer client connected: address=" + connection.getInetAddress());
            while (true) {
                try (ObjectInputStream stream = new ObjectInputStream(connection.getInputStream())) {
                    while (true) {
                        try {
                            Message msg = (Message) stream.readObject();

                            logger.debug("TCPReactiveServer received message: address="
                                    + connection.getInetAddress() + " message=" + msg.toString());

                            events.messageReceived(msg);
                        } catch (StreamCorruptedException | ClassNotFoundException e) {
                            logger.exception("TCPReactiveServer failed to deserialize class: address="
                                    + connection.getInetAddress(), e);
                        }
                    }
                }
            }
        } catch (EOFException e) {
            logger.info("TCPReactiveServer client disconnected: address=" + connection.getInetAddress());
        } catch (IOException e) {
            logger.exception("TCPReactiveServer client exception: address=" + connection.getInetAddress(), e);
        }
    }

    @Override
    public void close() throws IOException {
        if (serverSocket != null)
            serverSocket.close();
    }
}
