package dev.chords.microservices.benchmark;

import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.opentelemetry.api.OpenTelemetry;

import java.io.IOException;
import java.util.ArrayList;

public class ChainBenchmark {

    public record ChoreographyResult(long simulatedLatency, long total, ArrayList<Long> sidecarLatency) {
    }

    public record OrchestratorResult(long simulatedLatency, long total) {
    }

    public record Results(ArrayList<ChoreographyResult> choreography, ArrayList<OrchestratorResult> orchestrator) {
    }

    ChainService choreographyService;
    ChainOrchestratorClient orchestratorClient;
    Thread choreographyServerThread;

    ToxiproxyClient toxiClient;

    public ChainBenchmark(OpenTelemetry telemetry, String orchestratorAddress, String first, String nextSidecar, String toxiproxy) throws Exception {
        String[] orchestratorSplit = orchestratorAddress.split(":");
        orchestratorClient = new ChainOrchestratorClient(orchestratorSplit[0], Integer.parseInt(orchestratorSplit[1]), telemetry);

        choreographyService = ChainService.makeChainA(telemetry, first, nextSidecar);
        choreographyServerThread = choreographyService.listen();

        String[] toxiSplit = toxiproxy.split(":");
        toxiClient = new ToxiproxyClient(toxiSplit[0], Integer.parseInt(toxiSplit[1]));
    }

    public Results runBenchmark() throws Exception {
        System.out.println("Waiting for services to start up...");
        Thread.sleep(3000);

        final int STEP = 1;
        final int STEP_COUNT = 10;
        final int SAMPLES = 20;
        final int WARMUP = 100;

        clearLatencies();

        System.out.println("Warmup choreography");
        for (int i = 0; i < WARMUP; i++) {
            choreographyService.initiateRequestChain();
        }

        ArrayList<ChoreographyResult> choreographyResults = new ArrayList<>();

        System.out.println("Execute choreography");
        for (int latency = 0; latency < STEP_COUNT; latency++) {

            choreographicLatency(latency * STEP);

            for (int i = 0; i < SAMPLES; i++) {
                Long t1 = System.nanoTime();
                var results = choreographyService.initiateRequestChain();
                Long t2 = System.nanoTime();

                choreographyResults.add(new ChoreographyResult(latency * STEP, t2 - t1, results));
            }
        }

        clearLatencies();

        System.out.println("Warmup orchestrator");
        for (int i = 0; i < WARMUP; i++) {
            orchestratorClient.runOrchestrator();
        }

        ArrayList<OrchestratorResult> orchestratorResults = new ArrayList<>();

        System.out.println("Execute orchestrator");
        for (int latency = 0; latency < STEP_COUNT; latency++) {

            orchestratedLatency(latency * STEP);

            for (int i = 0; i < SAMPLES; i++) {
                var result = orchestratorClient.runOrchestrator();
                orchestratorResults.add(new OrchestratorResult(latency * STEP, result.endToEndTime()));
            }
        }

        return new Results(choreographyResults, orchestratorResults);
    }

    private void clearLatencies() {
        try {

            toxiClient.getProxies().forEach(proxy -> {
                try {
                    proxy.toxics().getAll().forEach(toxic -> {
                        try {
                            toxic.remove();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void choreographicLatency(long latency) {
        clearLatencies();

        try {
            toxiClient.getProxy("first_sidecar_proxy").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("first_sidecar_proxy").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("second_sidecar_proxy").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("second_sidecar_proxy").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("third_sidecar_proxy").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("third_sidecar_proxy").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void orchestratedLatency(long latency) {
        clearLatencies();

        try {
            toxiClient.getProxy("first_proxy").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("first_proxy").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("second_proxy").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("second_proxy").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("third_proxy").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("third_proxy").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
