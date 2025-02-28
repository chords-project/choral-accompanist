package dev.chords.microservices.benchmark;

import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.ReactiveClient;
import choral.reactive.ReactiveSymChannel;
import choral.reactive.Session;
import choral.reactive.ReactiveServer;
import choral.reactive.tracing.JaegerConfiguration;
import choral.reactive.tracing.TelemetrySession;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.io.Serializable;

public class ServiceA {

    private OpenTelemetry telemetry;
    private ReactiveServer serverA;
    private ClientConnectionManager connectionServiceB;

    public ServiceA(OpenTelemetry telemetry, String addressServiceB) throws Exception {
        this.telemetry = telemetry;
        this.connectionServiceB = ClientConnectionManager.makeConnectionManager(addressServiceB, telemetry);
        this.serverA = new ReactiveServer("serviceA", telemetry, ctx -> {
            System.out.println("ServiceA received new session");
        });
    }

    public void listen(String address) {
        Thread.ofVirtual()
                .name("serviceA")
                .start(() -> {
                    try {
                        serverA.listen("localhost:8201");
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

        serverA.registerSession(session, telemetrySession);

        try (Scope scope = span.makeCurrent();
                ReactiveClient client = new ReactiveClient(
                        connectionServiceB,
                        "serviceA",
                        telemetrySession);) {

            ReactiveSymChannel<Serializable> ch = new ReactiveSymChannel<>(client.chanA(session),
                    serverA.chanB(session, "serviceB"));

            SimpleChoreography_A chor = new SimpleChoreography_A(ch);
            chor.pingPong();
        } finally {
            span.end();
        }
    }

    public void startGreeting() throws Exception {
        Session session = Session.makeSession("greeting", "serviceA");

        Span span = telemetry
                .getTracer(JaegerConfiguration.TRACER_NAME)
                .spanBuilder("greeting")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("choreography.session", session.toString())
                .startSpan();

        TelemetrySession telemetrySession = new TelemetrySession(telemetry, session, span);

        serverA.registerSession(session, telemetrySession);

        try (Scope scope = span.makeCurrent();
                ReactiveClient client = new ReactiveClient(connectionServiceB,
                        "serviceA",
                        telemetrySession);) {

            ReactiveSymChannel<Serializable> ch = new ReactiveSymChannel<>(client.chanA(session),
                    serverA.chanB(session, "serviceB"));

            GreeterChoreography_A chor = new GreeterChoreography_A(ch);
            chor.greet();
        }

        span.end();
    }

    public void close() throws Exception {
        connectionServiceB.close();
        serverA.close();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Service A");

        final String JAEGER_ENDPOINT = "http://localhost:4317";
        OpenTelemetry telemetry = JaegerConfiguration.initTelemetry(JAEGER_ENDPOINT, "ServiceA");

        ServiceA service = new ServiceA(telemetry, "localhost:8202");
        service.listen("localhost:8201");

        service.startPingPong();

        service.close();
    }
}
