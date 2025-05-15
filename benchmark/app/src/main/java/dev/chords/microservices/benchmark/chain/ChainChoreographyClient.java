package dev.chords.microservices.benchmark.chain;

import accompanist.benchmark.chain.Chain;
import accompanist.benchmark.chain.ChoreographyGrpc;
import choral.reactive.tracing.JaegerConfiguration;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

import java.util.List;

public class ChainChoreographyClient {
    ManagedChannel channel;
    ChoreographyGrpc.ChoreographyBlockingStub blockingStub;
    Tracer tracer;

    ChainChoreographyClient(int port, OpenTelemetry telemetry) {
        this("localhost", port, telemetry);
    }

    ChainChoreographyClient(String address, int port, OpenTelemetry telemetry) {
        this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);

        this.channel = ManagedChannelBuilder.forAddress(address, port).usePlaintext().build();
        this.blockingStub = ChoreographyGrpc.newBlockingStub(channel);
    }

    public record Result(Long endToEndTime, List<Long> sidecarTimes) {
    }

    public ChainChoreographyClient.Result runChoreography(Chain.ChainLength chainLength) {
        Long t1 = System.nanoTime();
        var reply = blockingStub.start(Chain.ChoreographyRequest.newBuilder().setChainLength(chainLength).build());
        Long t2 = System.nanoTime();

        return new ChainChoreographyClient.Result(t2 - t1, reply.getSidecarTimesList());
    }
}
