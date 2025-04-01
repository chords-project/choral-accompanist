/*
 * This source file was generated by the Gradle 'init' task
 */
package dev.chords.microservices.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Callable;

import choral.reactive.tracing.JaegerConfiguration;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

public class Benchmark {

    final static String SERVICE_A = "localhost:8201";
    final static String SERVICE_B = "localhost:8202";
    final static int GRPC_PORT = 5430;

    interface TestAction<T> {
        void test(T value) throws Exception;
    }

    public static void benchmarkChoreography(TestAction<ServiceA> testAction) throws Exception {

        final String JAEGER_ENDPOINT = "http://localhost:4317";

        GrpcServer grpcServer = new GrpcServer();
        grpcServer.start(GRPC_PORT);

        ServiceA serviceA = new ServiceA(OpenTelemetrySdk.builder().build(), SERVICE_B);
        ServiceB serviceB = new ServiceB(OpenTelemetrySdk.builder().build(), SERVICE_A);

//        ServiceA serviceA = new ServiceA(JaegerConfiguration.initTelemetry(JAEGER_ENDPOINT, "ServiceA"), SERVICE_B);
//        ServiceB serviceB = new ServiceB(JaegerConfiguration.initTelemetry(JAEGER_ENDPOINT, "ServiceB"), SERVICE_A);

        serviceA.listen(SERVICE_A);
        serviceB.listen(SERVICE_B);

        testAction.test(serviceA);

        serviceA.close();
        serviceB.close();
        grpcServer.stop();
    }

    public static void benchmarkGrpc(TestAction<GrpcClient> testAction) throws Exception {
        final String JAEGER_ENDPOINT = "http://localhost:4317";

        GrpcServer server = new GrpcServer();
        GrpcClient client = new GrpcClient(GRPC_PORT, JaegerConfiguration.initTelemetry(JAEGER_ENDPOINT, "GrpcClient"));

        server.start(GRPC_PORT);

        testAction.test(client);

        client.shutdown();
        server.stop();
    }

    public static void measure(int n, Callable<Void> action) throws Exception {
        long startTime = System.nanoTime();
        action.call();
        long endTime = System.nanoTime();
        System.out.println("Warmup took " + (endTime - startTime) + " ms");

        var latencies = new ArrayList<Long>(n);

        for (int i = 0; i < n; i++) {
            startTime = System.nanoTime();
            action.call();
            endTime = System.nanoTime();

            latencies.add(endTime - startTime);
        }

        Collections.sort(latencies);

        long median = latencies.get(n / 2);
        long lowAvg = latencies.stream().limit(n / 2).reduce(0L, Long::sum) / (n / 2);

        System.out.println("Median: " + median / 1_000_000.0 + ", Low avg: " + lowAvg / 1_000_000.0);
    }

    public static void main(String[] args) throws Exception {

        OpenTelemetry telemetry = OpenTelemetry.noop();

        String benchmark = System.getenv("BENCHMARK");
        if (benchmark == null) {
            System.out.println("BENCHMARK=chain-initiator, SERVICE_NAME=name, NEXT_ADDRESS=address:port");
            System.out.println("BENCHMARK=chain-forwarder, SERVICE_NAME=name, NEXT_ADDRESS=address:port");
            System.exit(1);
        }

        String serviceName = System.getenv("SERVICE_NAME");
        String nextAddress = System.getenv("NEXT_ADDRESS");

        switch (benchmark) {
            case "chain-initiator": {
                var service = ChainService.makeInitiator(telemetry, serviceName, nextAddress);
                service.listen().join();
                break;
            }
            case "chain-forwarder": {
                var service = ChainService.makeForwarder(telemetry, serviceName, nextAddress);
                service.listen().join();
                break;
            }
        }

        /*Scanner input = new Scanner(System.in);
        System.out.print("Press Enter to perform benchmark...");
        input.nextLine();
        input.close();

        benchmarkChoreography(serviceA -> {
            measure(10_000, () -> {
                serviceA.startPingPong();
                return null;
            });

            measure(10_000, () -> {
                serviceA.startGreeting();
                return null;
            });
        });

        benchmarkGrpc(client -> {
            measure(10_000, () -> {
                client.greet("Name");
                return null;
            });
        });*/

    }
}
