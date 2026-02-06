package io.temporal.samples.ordersaga;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RestEndpoint {

    protected HttpServer server;

    public RestEndpoint(Events events) throws IOException {
        var port = Integer.parseInt(System.getenv().getOrDefault("LISTEN_PORT", "5000"));
        var address = new InetSocketAddress("0.0.0.0", port);
        server = HttpServer.create(address, 0);

        server.createContext("/orderFulfillment", exchange -> {
            System.out.println("Handling REST request at: /orderFulfillment");

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

                System.out.println("Did send " + statusCode + " reply to REST request at: /orderFulfillment");
            }
        });
    }

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(RestEndpoint.this::stop, "RestEndpoint_SHUTDOWN_HOOK"));


        System.out.println("Starting RestEndpoint on port " + server.getAddress().getPort());

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        server.start();

        try {
            while (!executor.isTerminated()) {
                var _ = executor.awaitTermination(1, TimeUnit.DAYS);
            }
        } catch (InterruptedException e) {
            // executor stopped
        }
    }

    public void stop() {
        server.stop(5);
    }

    public interface Events {
        Object orderFulfillment() throws Exception;
    }
}
