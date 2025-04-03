package dev.chords.microservices.benchmark;

import io.opentelemetry.api.OpenTelemetry;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;

public class ChainBenchmark {

    public record ChoreographyResult(long simulatedLatency, long total, ArrayList<Long> sidecarLatency) { }
    public record OrchestratorResult(long simulatedLatency, long total) { }
    public record Results(ArrayList<ChoreographyResult> choreography, ArrayList<OrchestratorResult> orchestrator) { }

    ChainService choreographyService;
    ChainOrchestrator orchestrator;
    Thread choreographyServerThread;

    public ChainBenchmark(OpenTelemetry telemetry, String first, String second, String third, String nextSidecar, String toxiproxy) throws Exception {
        orchestrator = new ChainOrchestrator(first, second, third, telemetry);

        choreographyService = ChainService.makeChainA(telemetry, first, nextSidecar);
        choreographyServerThread = choreographyService.listen();
    }

    public Results runBenchmark() throws Exception {
        System.out.println("Waiting for services to start up...");
        Thread.sleep(3000);

        System.out.println("Warmup choreography");
        for (int i = 0; i < 100; i++) {
            choreographyService.initiateRequestChain();
        }

        ArrayList<ChoreographyResult> choreographyResults = new ArrayList<>();

        System.out.println("Execute choreography");
        for (int i = 0; i < 100; i++) {
            Long t1 = System.nanoTime();
            var results = choreographyService.initiateRequestChain();
            Long t2 = System.nanoTime();

            choreographyResults.add(new ChoreographyResult(0, t2-t1, results));
        }


        System.out.println("Warmup orchestrator");
        for (int i = 0; i < 100; i++) {
            orchestrator.runOrchestrator();
        }

        ArrayList<OrchestratorResult> orchestratorResults = new ArrayList<>();

        System.out.println("Execute orchestrator");
        for (int i = 0; i < 100; i++) {
            var result = orchestrator.runOrchestrator();
            orchestratorResults.add(new OrchestratorResult(0, result));
        }

        return new Results(choreographyResults, orchestratorResults);
    }
}
