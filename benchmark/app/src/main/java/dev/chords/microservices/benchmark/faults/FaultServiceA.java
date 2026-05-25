package dev.chords.microservices.benchmark.faults;

import choral.accompanist.faulttolerance.FaultTolerantServer;
import choral.accompanist.faulttolerance.RMQChannelReceiver;
import choral.accompanist.faulttolerance.RMQChannelSender;
import choral.accompanist.faulttolerance.SQLDataStore;
import choral.accompanist.ReactiveSymChannel;
import choral.accompanist.Session;
import choral.accompanist.connection.ClientConnectionManager;
import choral.accompanist.tracing.JaegerConfiguration;
import choral.accompanist.tracing.TelemetrySession;
import com.rabbitmq.client.ConnectionFactory;
import dev.chords.microservices.benchmark.SimpleChoreography_B;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;

import java.util.Set;

public class FaultServiceA {
    private OpenTelemetry telemetry;
    private FaultTolerantServer serverA;
    //private ClientConnectionManager connectionServiceB;

    public FaultServiceA(OpenTelemetry telemetry, String rmqAddress) throws Exception {
        this.telemetry = telemetry;

        var connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(rmqAddress);
        var connection = connectionFactory.newConnection();

        //this.connectionServiceB = new RMQChannelSender(connection, "serviceB");
        var clientConn = RMQChannelSender.factory(connection);
        var serverConn = RMQChannelReceiver.factory();

        SQLDataStore dataStore = SQLDataStore.createHikariDataStore(
                "jdbc:postgresql://localhost:5432/benchmark_service_a",
                "postgres",
                "postgres",
                Set.of()
        );

        this.serverA = new FaultTolerantServer(dataStore, clientConn, serverConn, "serviceA", telemetry, ctx -> {
            switch (ctx.session.choreographyName()) {
                case "ping-pong":
                    SimpleChoreography_B pingPongChor = new SimpleChoreography_B(
                            ctx.symChan("serviceB", "serviceB"));

                    pingPongChor.pingPong();

                    return null;
                default:
                    throw new RuntimeException("unknown choreography: " + ctx.session.choreographyName());
            }
        });
    }

    public void listen(String address) {
        Thread.ofVirtual()
                .name("serviceA")
                .start(() -> {
                    try {
                        serverA.listen(address);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    public void startPingPong() throws Exception {
        Session session = Session.makeSession("ping-pong", "serviceA");

        Span span = telemetry
                .getTracer(JaegerConfiguration.TRACER_NAME)
                .spanBuilder("ping-pong")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("choreography.session", session.toString())
                .startSpan();

        TelemetrySession telemetrySession = new TelemetrySession(telemetry, session, span);

        serverA.invokeManualSession(telemetrySession);
    }

    public void close() throws Exception {
        //connectionServiceB.close();
        serverA.close();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Service A");

        //final String JAEGER_ENDPOINT = "http://localhost:4317";
        //OpenTelemetry telemetry = JaegerConfiguration.initTelemetry(JAEGER_ENDPOINT, "ServiceA");

        FaultServiceA service = new FaultServiceA(OpenTelemetry.noop(), "localhost");
        service.listen("localhost");

        service.startPingPong();

        service.close();
    }
}
