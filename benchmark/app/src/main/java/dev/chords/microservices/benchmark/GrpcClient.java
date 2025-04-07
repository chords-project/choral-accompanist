package dev.chords.microservices.benchmark;

import java.util.concurrent.TimeUnit;

import choral.reactive.tracing.JaegerConfiguration;
import accompanist.benchmark.greeting.GreeterGrpc;
import accompanist.benchmark.greeting.GreeterGrpc.GreeterBlockingStub;
import accompanist.benchmark.greeting.Greeting.HelloReply;
import accompanist.benchmark.greeting.Greeting.HelloRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

public class GrpcClient implements GreeterService {

    ManagedChannel channel;
    GreeterBlockingStub blockingStub;
    // GreeterFutureStub futureStub;
    Tracer tracer;

    GrpcClient(int port, OpenTelemetry telemetry) {
        this("localhost", port, telemetry);
    }

    GrpcClient(String address, int port, OpenTelemetry telemetry) {
        this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);

        this.channel = ManagedChannelBuilder.forAddress(address, port).usePlaintext().build();
        this.blockingStub = GreeterGrpc.newBlockingStub(channel);
        // this.futureStub = GreeterGrpc.newFutureStub(channel);
    }

    @Override
    public String greet(String name) {
        Span span = tracer.spanBuilder("GrpcClient.greet").setAttribute("request.name", name).startSpan();

        HelloReply reply;
        try (Scope scope = span.makeCurrent();) {
            reply = blockingStub.sayHello(HelloRequest.newBuilder().setName(name).build());
            // reply =
            // futureStub.sayHello(HelloRequest.newBuilder().setName(name).build()).get();
            // } catch (InterruptedException | ExecutionException e) {
            // throw new RuntimeException(e);
        } finally {
            span.end();
        }

        return reply.getMessage();
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
}
