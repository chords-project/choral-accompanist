package dev.chords.microservices.benchmark;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import greeting.GreeterGrpc.GreeterImplBase;
import greeting.Greeting.HelloReply;
import greeting.Greeting.HelloRequest;
import io.grpc.stub.StreamObserver;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;

public class GrpcServer {

    private Server server;

    public void start(int port) throws IOException {
        server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(new GreeterImpl())
                .build()
                .start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may have been reset by its JVM shutdown
            // hook.
            System.err.println("GrpcServer: shutting down gRPC server since JVM is shutting down");
            try {
                GrpcServer.this.stop();
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
            }
            System.err.println("GrpcServer: server shut down");
        }));
    }

    public void stop() throws InterruptedException {
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

    static class GreeterImpl extends GreeterImplBase {
        @Override
        public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            String name = request.getName();
            HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + name + "!").build();

            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }
}
