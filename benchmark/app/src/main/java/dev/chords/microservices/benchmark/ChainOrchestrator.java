package dev.chords.microservices.benchmark;

import greeting.Greeting;
import io.opentelemetry.api.OpenTelemetry;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;

public class ChainOrchestrator implements Closeable {

    GrpcClient firstClient;
    GrpcClient secondClient;
    GrpcClient thirdClient;

    public ChainOrchestrator(String first, String second, String third, OpenTelemetry telemetry) {
        String[] firstSplit = first.split(":");
        firstClient = new GrpcClient(firstSplit[0], Integer.parseInt(firstSplit[1]), telemetry);

        String[] secondSplit = second.split(":");
        secondClient = new GrpcClient(secondSplit[0], Integer.parseInt(secondSplit[1]), telemetry);

        String[] thirdSplit = third.split(":");
        thirdClient = new GrpcClient(thirdSplit[0], Integer.parseInt(thirdSplit[1]), telemetry);
    }

    public Long runOrchestrator() {
        Long t1 = System.nanoTime();
        firstClient.blockingStub.sayHello(Greeting.HelloRequest.newBuilder().setName("Hello First").build());
        secondClient.blockingStub.sayHello(Greeting.HelloRequest.newBuilder().setName("Hello Second").build());
        thirdClient.blockingStub.sayHello(Greeting.HelloRequest.newBuilder().setName("Hello Third").build());
        Long t2 = System.nanoTime();

        System.out.println("Orchestrator completed in: " + (t2-t1));

        return t2 - t1;
    }

    @Override
    public void close() throws IOException {
        try {
            firstClient.shutdown();
            secondClient.shutdown();
            thirdClient.shutdown();
        } catch (InterruptedException ignored) {}
    }
}
