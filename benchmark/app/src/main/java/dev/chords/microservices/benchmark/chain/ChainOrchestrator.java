package dev.chords.microservices.benchmark.chain;

import accompanist.benchmark.chain.Chain;
import accompanist.benchmark.chain.OrchestratorGrpc;
import accompanist.benchmark.greeting.Greeting;
import dev.chords.microservices.benchmark.GrpcClient;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.OpenTelemetry;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ChainOrchestrator implements Closeable {

    GrpcClient firstClient;
    GrpcClient secondClient;
    GrpcClient thirdClient;
    GrpcClient fourthClient;
    GrpcClient fifthClient;

    private Server server;

    public ChainOrchestrator(String first, String second, String third, String fourth, String fifth, OpenTelemetry telemetry) {
        String[] firstSplit = first.split(":");
        firstClient = new GrpcClient(firstSplit[0], Integer.parseInt(firstSplit[1]), telemetry);

        String[] secondSplit = second.split(":");
        secondClient = new GrpcClient(secondSplit[0], Integer.parseInt(secondSplit[1]), telemetry);

        String[] thirdSplit = third.split(":");
        thirdClient = new GrpcClient(thirdSplit[0], Integer.parseInt(thirdSplit[1]), telemetry);

        String[] fourthSplit = fourth.split(":");
        fourthClient = new GrpcClient(fourthSplit[0], Integer.parseInt(fourthSplit[1]), telemetry);

        String[] fifthSplit = fifth.split(":");
        fifthClient = new GrpcClient(fifthSplit[0], Integer.parseInt(fifthSplit[1]), telemetry);
    }

    public void start(int port) throws IOException {
        server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(new GrpcOrchestratorImpl())
                .build()
                .start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may have been reset by its JVM shutdown
            // hook.
            System.err.println("ChainOrchestrator: server shutting down gRPC server since JVM is shutting down");
            try {
                ChainOrchestrator.this.stopServer();
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
            }
            System.err.println("ChainOrchestrator: server shut down");
        }));
    }

    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    public void stopServer() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            firstClient.shutdown();
            secondClient.shutdown();
            thirdClient.shutdown();
            fourthClient.shutdown();
            fifthClient.shutdown();

            this.stopServer();
        } catch (InterruptedException ignored) {
        }
    }

    class GrpcOrchestratorImpl extends OrchestratorGrpc.OrchestratorImplBase {
        @Override
        public void start(Chain.OrchestratorRequest request, StreamObserver<Chain.OrchestratorReply> responseObserver) {
            System.out.println("Starting orchestrator...");

            Long t1 = System.nanoTime();

            switch (request.getChainLength()) {
                case ONE -> {
                    firstClient.greet("Hello First");
                }
                case THREE -> {
                    firstClient.greet("Hello First");
                    secondClient.greet("Hello Second");
                    thirdClient.greet("Hello Third");
                }
                case FIVE -> {
                    firstClient.greet("Hello First");
                    secondClient.greet("Hello Second");
                    thirdClient.greet("Hello Third");
                    fourthClient.greet("Hello Fourth");
                    fifthClient.greet("Hello Fifth");
                }
                default -> throw new IllegalStateException("Unexpected value: " + request.getChainLength());
            }
            Long t2 = System.nanoTime();

            Long time = t2 - t1;
            System.out.println("Orchestrator completed in: " + time);

            responseObserver.onNext(Chain.OrchestratorReply.newBuilder().setTime(time).build());
            responseObserver.onCompleted();
        }
    }

}
