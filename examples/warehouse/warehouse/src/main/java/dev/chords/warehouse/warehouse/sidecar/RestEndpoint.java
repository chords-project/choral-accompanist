package dev.chords.warehouse.warehouse.sidecar;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class RestEndpoint {

    protected HttpServer server;
    protected WarehouseSidecar warehouse;
    private final Logger logger;

    public RestEndpoint(Events events) throws IOException {
        logger = LoggerFactory.getLogger(RestEndpoint.class);
        var port = Integer.parseInt(System.getenv().getOrDefault("LISTEN_PORT", "5000"));
        var address = new InetSocketAddress("0.0.0.0", port);
        server = HttpServer.create(address, 0);

        server.createContext("/orderFulfillment", exchange -> {
            logger.info("Handling REST request at: /orderFulfillment");

            String message = "";
            boolean success = false;

            try {
                Object result = events.orderFulfillment();
                if (result instanceof Exception) {
                    message = "order exception: " + ((Exception) result).getMessage();
                    success = false;
                } else {
                    message = "success: " + result.toString();
                    success = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
                message = "order failed: " + e.getMessage();
                success = false;
            } finally {
                var statusCode = success ? 200 : 500;
                exchange.sendResponseHeaders(statusCode, message.length());
                exchange.getResponseBody().write(message.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();

                logger.info("Did send {} reply to REST request at: /orderFulfillment", statusCode);
            }
        });
    }

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(RestEndpoint.this::stop, "RestEndpoint_SHUTDOWN_HOOK"));


        logger.info("Starting RestEndpoint on port " + server.getAddress().getPort());
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public void stop() {
        server.stop(5);
    }

    public interface Events {
        Object orderFulfillment() throws Exception;
    }
}
