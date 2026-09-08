package choral.accompanist.examples.warehouse.payment.service;

import choral.accompanist.examples.warehouse.proto.PaymentGrpc;
import choral.accompanist.examples.warehouse.proto.PaymentOuterClass;
import choral.accompanist.examples.warehouse.proto.PaymentOuterClass.Empty;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class PaymentService {

    public static void main(String[] args) throws Exception {
        var service = new PaymentService();
        var port = Integer.parseInt(System.getenv().getOrDefault("PORT", "2000"));
        service.start(port);
        service.blockUntilShutdown();
    }

    private Server server;

    public void start(int port) throws IOException {
        server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(new PaymentImpl())
                .build()
                .start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may have been reset by its JVM shutdown
            // hook.
            System.err.println("GrpcServer: shutting down gRPC server since JVM is shutting down");
            try {
                PaymentService.this.stop();
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

    static class PaymentImpl extends PaymentGrpc.PaymentImplBase {
        @Override
        public void commitTakeMoneyFromCustomer(Empty request, StreamObserver<PaymentOuterClass.Empty> responseObserver) {
            System.out.println("- Payment commit transaction: takeMoneyFromCustomer");
            responseObserver.onNext(PaymentOuterClass.Empty.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void compensateTakeMoneyFromCustomer(choral.accompanist.examples.warehouse.proto.PaymentOuterClass.Empty request, StreamObserver<PaymentOuterClass.Empty> responseObserver) {
            System.out.println("- Payment compensate transaction: takeMoneyFromCustomer");
            responseObserver.onNext(PaymentOuterClass.Empty.newBuilder().build());
            responseObserver.onCompleted();
        }
    }
}
