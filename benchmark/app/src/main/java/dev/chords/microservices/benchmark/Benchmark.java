package dev.chords.microservices.benchmark;

import java.util.function.Function;

import accompanist.benchmark.chain.Chain;
import io.opentelemetry.api.OpenTelemetry;

public class Benchmark {

    public static void main(String[] args) throws Exception {

        OpenTelemetry telemetry = OpenTelemetry.noop();

        String benchmark = System.getenv("BENCHMARK");
        if (benchmark == null) {
            System.out.println("Usage of accompanist-benchmark:");
            System.out.println("    BENCHMARK=chain-sidecar-start, PORT=port NEXT_SIDECAR=address:port");
            System.out.println("    BENCHMARK=chain-sidecar-a, SERVICE=address:port NEXT_SIDECAR=address:port START=address:port");
            System.out.println("    BENCHMARK=chain-sidecar-b, SERVICE=address:port NEXT_SIDECAR=address:port");
            System.out.println("    BENCHMARK=chain-sidecar-c, SERVICE=address:port NEXT_SIDECAR=address:port START=address:port");
            System.out.println("    BENCHMARK=chain-sidecar-d, SERVICE=address:port NEXT_SIDECAR=address:port");
            System.out.println("    BENCHMARK=chain-sidecar-e, SERVICE=address:port NEXT_SIDECAR=address:port");
            System.out.println("    BENCHMARK=greeter PORT=port");
            System.out.println("    BENCHMARK=chain-client-choreography SERVICE=address:port");
            System.out.println("    BENCHMARK=chain-orchestrator PORT=port FIRST=address:port SECOND=address:port THIRD=address:port FOURTH=address:port FIFTH=address:port");
            System.out.println("    BENCHMARK=chain-benchmark ORCHESTRATOR=address:port CHOREOGRAPHY=address:port TOXIPROXY=address:port");
            System.exit(1);
        }

        String nextSidecar = System.getenv("NEXT_SIDECAR");
        String startAddress = System.getenv("START");
        String serviceAddress = System.getenv("SERVICE");
        String orchestratorAddress = System.getenv("ORCHESTRATOR");
        String choreographyAddress = System.getenv("CHOREOGRAPHY");
        String port = System.getenv("PORT");
        String toxiproxy = System.getenv("TOXIPROXY");

        String first = System.getenv("FIRST");
        String second = System.getenv("SECOND");
        String third = System.getenv("THIRD");
        String fourth = System.getenv("FOURTH");
        String fifth = System.getenv("FIFTH");

        switch (benchmark) {
            case "chain-sidecar-start" -> {
                var sidecar = new ChainChoreographyStart(telemetry, nextSidecar);
                sidecar.start(Integer.parseInt(port));
                sidecar.blockUntilShutdown();
            }
            case "chain-sidecar-a" -> {
                var sidecar = ChainSidecar.makeChainA(telemetry, serviceAddress, nextSidecar, startAddress);
                sidecar.listen().join();
            }
            case "chain-sidecar-b" -> {
                var sidecar = ChainSidecar.makeChainB(telemetry, serviceAddress, nextSidecar, startAddress);
                sidecar.listen().join();
            }
            case "chain-sidecar-c" -> {
                var service = ChainSidecar.makeChainC(telemetry, serviceAddress, nextSidecar, startAddress);
                service.listen().join();
            }
            case "chain-sidecar-d" -> {
                var service = ChainSidecar.makeChainD(telemetry, serviceAddress, nextSidecar, startAddress);
                service.listen().join();
            }
            case "chain-sidecar-e" -> {
                var service = ChainSidecar.makeChainE(telemetry, serviceAddress, nextSidecar);
                service.listen().join();
            }
            case "greeter" -> {
                GrpcServer server = new GrpcServer();
                server.start(Integer.parseInt(port));
                server.blockUntilShutdown();
            }
            case "chain-orchestrator" -> {
                ChainOrchestrator orchestrator = new ChainOrchestrator(first, second, third, fourth, fifth, telemetry);
                orchestrator.start(Integer.parseInt(port));
                orchestrator.blockUntilShutdown();
                orchestrator.close();
            }
            case "chain-benchmark" -> {
                ChainBenchmark bm = new ChainBenchmark(telemetry, orchestratorAddress, choreographyAddress, toxiproxy);
                var result = bm.runBenchmark();

                Function<Chain.ChainLength, Integer> lengthToNumber = len -> switch (len) {
                    case ONE -> 1;
                    case TWO -> 2;
                    case THREE -> 3;
                    case FOUR -> 4;
                    case FIVE -> 5;
                    default -> throw new IllegalStateException("Unexpected value: " + len);
                };

                System.out.println("\nEquidistant (Symmetric) Choreography:");
                System.out.println("chain_length;sidecar;total;simulated_latency");
                for (var r : result.equidistantChoreography()) {
                    Long sidecarLatency = r.sidecarLatency().stream().reduce(0L, Long::sum);
                    Integer chainLength = lengthToNumber.apply(r.chainLength());
                    System.out.println(chainLength + ";" + sidecarLatency + ";" + r.total() + ";" + r.simulatedLatency());
                }

                System.out.println("\nAsymmetric Choreography:");
                System.out.println("chain_length;sidecar;total;simulated_latency");
                for (var r : result.asymmetricChoreography()) {
                    Long sidecarLatency = r.sidecarLatency().stream().reduce(0L, Long::sum);
                    Integer chainLength = lengthToNumber.apply(r.chainLength());
                    System.out.println(chainLength + ";" + sidecarLatency + ";" + r.total() + ";" + r.simulatedLatency());
                }

                System.out.println("\nOrchestrator:");
                System.out.println("chain_length;end_to_end_time;orchestrator_time;simulated_latency");
                for (var r : result.orchestrator()) {
                    Integer chainLength = lengthToNumber.apply(r.chainLength());
                    System.out.println(chainLength + ";" + r.endToEndTime() + ";" + r.orchestratorTime() + ";" + r.simulatedLatency());
                }
            }
            default -> {
                System.err.println("Invalid benchmark type: " + benchmark);
                System.exit(1);
            }
        }

    }
}
