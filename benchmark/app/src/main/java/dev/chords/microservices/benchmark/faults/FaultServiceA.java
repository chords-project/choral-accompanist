package dev.chords.microservices.benchmark.faults;

import choral.faulttolerance.FaultDataStore;
import choral.faulttolerance.FaultTolerantServer;
import choral.faulttolerance.RMQChannelSender;
import choral.faulttolerance.SqlDataStore;
import choral.reactive.ReactiveSymChannel;
import choral.reactive.Session;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.TelemetrySession;
import com.rabbitmq.client.ConnectionFactory;
import dev.chords.microservices.benchmark.SimpleChoreography_A;
import dev.chords.microservices.benchmark.SimpleChoreography_B;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;

import java.io.Serializable;
import java.sql.DriverManager;

public class FaultServiceA {
    private OpenTelemetry telemetry;
    private FaultTolerantServer serverA;
    private ClientConnectionManager connectionServiceB;

    public FaultServiceA(OpenTelemetry telemetry, String rmqAddress) throws Exception {
        this.telemetry = telemetry;

        var connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(rmqAddress);
        var connection = connectionFactory.newConnection();

        this.connectionServiceB = new RMQChannelSender(connection, "serviceB");


        var dbCon = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/benchmark_service_a",
                "postgres",
                "postgres");
        FaultDataStore dataStore = new SqlDataStore(dbCon);

        this.serverA = new FaultTolerantServer(dataStore, connection, "serviceA", telemetry, ctx -> {
            switch (ctx.session.choreographyName()) {
                case "ping-pong":
                    SimpleChoreography_B pingPongChor = new SimpleChoreography_B(
                            ctx.symChan("serviceB"));

                    pingPongChor.pingPong();

                    break;
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
        connectionServiceB.close();
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
