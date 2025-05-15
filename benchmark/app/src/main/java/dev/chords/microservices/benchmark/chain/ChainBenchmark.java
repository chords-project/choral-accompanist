package dev.chords.microservices.benchmark.chain;

import accompanist.benchmark.chain.Chain;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.opentelemetry.api.OpenTelemetry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ChainBenchmark {

    public record ChoreographyResult(Chain.ChainLength chainLength, long simulatedLatency, long total,
                                     List<Long> sidecarLatency) {
    }

    public record OrchestratorResult(Chain.ChainLength chainLength, long simulatedLatency, long endToEndTime,
                                     long orchestratorTime) {
    }

    public record Results(List<ChoreographyResult> choreography,
                          List<OrchestratorResult> orchestrator) {
    }

    ChainOrchestratorClient orchestratorClient;
    ChainChoreographyClient choreographyClient;

    ToxiproxyClient toxiClient;

    public ChainBenchmark(OpenTelemetry telemetry, String orchestratorAddress, String choreographyAddress, String toxiproxy) throws Exception {
        String[] orchestratorSplit = orchestratorAddress.split(":");
        orchestratorClient = new ChainOrchestratorClient(orchestratorSplit[0], Integer.parseInt(orchestratorSplit[1]), telemetry);

        String[] choreographySplit = choreographyAddress.split(":");
        choreographyClient = new ChainChoreographyClient(choreographySplit[0], Integer.parseInt(choreographySplit[1]), telemetry);

        String[] toxiSplit = toxiproxy.split(":");
        toxiClient = new ToxiproxyClient(toxiSplit[0], Integer.parseInt(toxiSplit[1]));
    }

    public Results runBenchmark() throws Exception {
        System.out.println("Waiting for services to start up...");
        Thread.sleep(3000);

        final int STEP = 3;
        final int STEP_COUNT = 10;
        final int SAMPLES = 20;
        final int WARMUP = 1_000;

        ArrayList<ChoreographyResult> choreographyResults = new ArrayList<>();
        ArrayList<OrchestratorResult> orchestratorResults = new ArrayList<>();

        var chainLengthValues = List.of(Chain.ChainLength.ONE, Chain.ChainLength.THREE, Chain.ChainLength.FIVE);
        for (Chain.ChainLength chainLength : chainLengthValues) {
            System.out.println("--- Running benchmark for chain length " + chainLength + " ---");
            clearLatencies();

            System.out.println("Warmup orchestrator");
            for (int i = 0; i < WARMUP; i++) {
                orchestratorClient.runOrchestrator(chainLength);
            }

            System.out.println("Execute orchestrator");
            for (int latency = 0; latency < STEP_COUNT; latency++) {

                orchestratedLatency(latency * STEP);

                for (int i = 0; i < SAMPLES; i++) {
                    var result = orchestratorClient.runOrchestrator(chainLength);
                    orchestratorResults.add(new OrchestratorResult(chainLength, latency * STEP, result.endToEndTime(), result.orchestratorTime()));
                }
            }

            clearLatencies();

            System.out.println("Warmup choreography");
            for (int i = 0; i < WARMUP; i++) {
                choreographyClient.runChoreography(chainLength);
            }

            System.out.println("Execute choreography");
            for (int latency = 0; latency < STEP_COUNT; latency++) {

                choreographicLatency(latency * STEP);

                for (int i = 0; i < SAMPLES; i++) {
                    Long t1 = System.nanoTime();
                    var result = choreographyClient.runChoreography(chainLength);
                    Long t2 = System.nanoTime();

                    choreographyResults.add(new ChoreographyResult(chainLength, latency * STEP, t2 - t1, result.sidecarTimes()));
                }
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
            toxiClient.getProxy("sidecar_a_intra").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("sidecar_a_intra").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("sidecar_b_intra").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("sidecar_b_intra").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("sidecar_c_intra").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("sidecar_c_intra").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("sidecar_d_intra").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("sidecar_d_intra").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("sidecar_e_intra").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("sidecar_e_intra").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("sidecar_start_intra").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("sidecar_start_intra").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void orchestratedLatency(long latency) {
        clearLatencies();

        try {
            toxiClient.getProxy("service_a_proxy").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("service_a_proxy").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("service_b_proxy").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("service_b_proxy").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("service_c_proxy").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("service_c_proxy").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("service_d_proxy").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("service_d_proxy").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("service_e_proxy").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("service_e_proxy").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

            toxiClient.getProxy("orchestrator_proxy").toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, latency);
            toxiClient.getProxy("orchestrator_proxy").toxics().latency("latency-up", ToxicDirection.UPSTREAM, latency);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
