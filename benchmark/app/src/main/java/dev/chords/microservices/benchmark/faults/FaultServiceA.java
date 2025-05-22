package dev.chords.microservices.benchmark.faults;

import choral.faulttolerance.FaultTolerantServer;
import choral.faulttolerance.RMQChannelSender;
import choral.reactive.ReactiveClient;
import choral.reactive.ReactiveServer;
import choral.reactive.ReactiveSymChannel;
import choral.reactive.Session;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.TelemetrySession;
import com.rabbitmq.client.ConnectionFactory;
import dev.chords.microservices.benchmark.SimpleChoreography_A;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;

import java.io.Serializable;

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

        this.serverA = new FaultTolerantServer(connection, "serviceA", telemetry, ctx -> {
            System.out.println("ServiceA received new session");
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


        try (var ctx = serverA.registerSession(session, telemetrySession);
             Scope scope = span.makeCurrent();) {

            ReactiveSymChannel<Serializable> ch = ctx.symChan("serviceB");

            SimpleChoreography_A chor = new SimpleChoreography_A(ch);
            chor.pingPong();
        } finally {
            span.end();
        }
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
