package dev.chords.microservices.benchmark.faults;

import choral.faulttolerance.FaultTolerantServer;
import choral.faulttolerance.SQLDataStore;
import choral.reactive.ReactiveServer;
import com.rabbitmq.client.ConnectionFactory;
import dev.chords.microservices.benchmark.SimpleChoreography_B;
import io.opentelemetry.api.OpenTelemetry;

public class FaultServiceB {
    private OpenTelemetry telemetry;
    private ReactiveServer serverB;

    public FaultServiceB(OpenTelemetry telemetry, String rmqAddress) throws Exception {
        this.telemetry = telemetry;

        var connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(rmqAddress);
        var connection = connectionFactory.newConnection();

        SQLDataStore dataStore = SQLDataStore.createHikariDataStore(
                "jdbc:postgresql://localhost:5432/benchmark_service_b",
                "postgres",
                "postgres"
        );

        this.serverB = new FaultTolerantServer(dataStore, connection, "serviceB", telemetry, ctx -> {
            switch (ctx.session.choreographyName()) {
                case "ping-pong":
                    SimpleChoreography_B pingPongChor = new SimpleChoreography_B(
                            ctx.symChan("serviceA"));

                    pingPongChor.pingPong();

                    break;
                default:
                    throw new RuntimeException("unknown choreography: " + ctx.session.choreographyName());
            }
        });
    }

    public void listen(String address) {
        Thread.ofVirtual()
                .name("serviceB")
                .start(() -> {
                    try {
                        serverB.listen(address);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    public void close() throws Exception {
        serverB.close();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Service B");

        //final String JAEGER_ENDPOINT = "http://localhost:4317";
        //OpenTelemetry telemetry = JaegerConfiguration.initTelemetry(JAEGER_ENDPOINT, "ServiceB");

        FaultServiceB service = new FaultServiceB(OpenTelemetry.noop(), "localhost");
        service.serverB.listen("localhost");
    }
}
