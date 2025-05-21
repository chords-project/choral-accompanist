package dev.chords.microservices.benchmark.faults;

import choral.faulttolerance.FaultTolerantServer;
import choral.faulttolerance.RMQChannelSender;
import choral.reactive.ReactiveServer;
import choral.reactive.connection.ClientConnectionManager;
import com.rabbitmq.client.ConnectionFactory;
import dev.chords.microservices.benchmark.*;
import io.opentelemetry.api.OpenTelemetry;

public class FaultServiceB {
    private OpenTelemetry telemetry;
    private ReactiveServer serverB;
    private ClientConnectionManager connectionServiceA;

    public FaultServiceB(OpenTelemetry telemetry, String rmqAddress) throws Exception {
        this.telemetry = telemetry;

        var connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(rmqAddress);
        var connection = connectionFactory.newConnection();

        this.connectionServiceA = new RMQChannelSender(connection, "serviceA");

        this.serverB = new FaultTolerantServer(connection, "serviceB", telemetry, ctx -> {
            switch (ctx.session.choreographyName()) {
                case "ping-pong":
                    SimpleChoreography_B pingPongChor = new SimpleChoreography_B(
                            ctx.symChan("serviceA", connectionServiceA));

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
        connectionServiceA.close();
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
