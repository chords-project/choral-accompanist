package dev.chords.microservices.benchmark.chain;

import accompanist.benchmark.chain.Chain;
import accompanist.benchmark.chain.ChoreographyGrpc;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.OpenTelemetry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class ChainChoreographyStart {

    OpenTelemetry telemetry;
    ChainSidecar sidecar;
    private Server server;

    public ChainChoreographyStart(OpenTelemetry telemetry, String nextSidecarAddress) throws Exception {
        this.telemetry = telemetry;
        this.sidecar = ChainSidecar.makeChainStart(telemetry, nextSidecarAddress);
    }

    public void start(int port) throws IOException {
        server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(new GrpcChoreographyImpl())
                .build()
                .start();

        System.out.println("ChainChoreographySidecar gRPC server running on port " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may have been reset by its JVM shutdown
            // hook.
            System.err.println("ChainChoreographySidecar: shutting down gRPC server since JVM is shutting down");
            try {
                ChainChoreographyStart.this.stop();
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
            System.err.println("ChainChoreographySidecar: server shut down");
        }));

        sidecar.listen();
    }

    public void stop() throws Exception {
        sidecar.stop();
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon
     * threads.
     */
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    class GrpcChoreographyImpl extends ChoreographyGrpc.ChoreographyImplBase {
        @Override
        public void start(Chain.ChoreographyRequest request, StreamObserver<Chain.ChoreographyReply> responseObserver) {
            try {
                Long t1 = System.nanoTime();
                ArrayList<Long> result = sidecar.initiateRequestChain(request.getChainLength());
                Long t2 = System.nanoTime();

                Long time = t2 - t1;

                responseObserver.onNext(Chain.ChoreographyReply.newBuilder()
                        .addAllSidecarTimes(result)
                        .setTime(time)
                        .build()
                );
            } catch (Exception e) {
                e.printStackTrace();
                responseObserver.onError(e);
            } finally {
                responseObserver.onCompleted();
            }
        }
    }

}
