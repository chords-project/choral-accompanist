package dev.chords.microservices.benchmark;

import accompanist.benchmark.chain.Chain;
import accompanist.benchmark.chain.OrchestratorGrpc;
import choral.reactive.tracing.JaegerConfiguration;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

public class ChainOrchestratorClient {

    ManagedChannel channel;
    OrchestratorGrpc.OrchestratorBlockingStub blockingStub;
    Tracer tracer;

    ChainOrchestratorClient(int port, OpenTelemetry telemetry) {
        this("localhost", port, telemetry);
    }

    ChainOrchestratorClient(String address, int port, OpenTelemetry telemetry) {
        this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);

        this.channel = ManagedChannelBuilder.forAddress(address, port).usePlaintext().build();
        this.blockingStub = OrchestratorGrpc.newBlockingStub(channel);
    }

    public record Result(Long endToEndTime, Long orchestratorTime) {}

    public Result runOrchestrator() {
        Long t1 = System.nanoTime();
        Long time = blockingStub.start(Chain.OrchestratorRequest.newBuilder().build()).getTime();
        Long t2 = System.nanoTime();

        return new Result(t2-t1, time);
    }
}
