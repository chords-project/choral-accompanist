package io.temporal.samples.ordersaga;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;

/** Readiness is exposed only after worker polling has started. */
final class WorkerHealth {
    static void start() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8081), 0);
        server.createContext("/ready", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }
}
