package dev.chords.microservices.benchmark.chain;

import accompanist.benchmark.chain.Chain;
import choral.reactive.ReactiveClient;
import choral.reactive.ReactiveServer;
import choral.reactive.ReactiveSymChannel;
import choral.reactive.Session;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.TelemetrySession;
import dev.chords.microservices.benchmark.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;

import java.util.ArrayList;

public class ChainSidecar {
    private ReactiveServer server;
    private String serviceName;
    private ClientConnectionManager nextSidecarConnection;
    private GrpcClient grpcClient;
    private OpenTelemetry telemetry;

    private ChainSidecar(ReactiveServer server, String serviceName, ClientConnectionManager nextSidecarConnection,
                         GrpcClient grpcClient, OpenTelemetry telemetry) {
        this.server = server;
        this.serviceName = serviceName;
        this.nextSidecarConnection = nextSidecarConnection;
        this.grpcClient = grpcClient;
        this.telemetry = telemetry;
    }

    public static ChainSidecar makeChainStart(OpenTelemetry telemetry, String nextSidecarAddress) throws Exception {
        var nextSidecarConnection = ClientConnectionManager.makeConnectionManager(nextSidecarAddress, telemetry);

        var server = new ReactiveServer("CHAIN_START", telemetry, ctx -> {
            //System.out.println("CHAIN_START received new session");
        });

        return new ChainSidecar(server, "CHAIN_START", nextSidecarConnection, null, telemetry);
    }

    public static ChainSidecar makeChainA(OpenTelemetry telemetry, String service, String nextSidecarAddress, String startAddress)
            throws Exception {
        var nextSidecarConnection = ClientConnectionManager.makeConnectionManager(nextSidecarAddress, telemetry);
        var startConnection = ClientConnectionManager.makeConnectionManager(startAddress, telemetry);

        String[] sidecarSplit = service.split(":");
        var grpcClient = new GrpcClient(sidecarSplit[0], Integer.parseInt(sidecarSplit[1]), telemetry);

        var server = new ReactiveServer("CHAIN_A", telemetry, ctx -> {
            //System.out.println("CHAIN_A received new session");

            switch (ctx.session.choreographyName()) {
                case "chain1" -> {
                    var chanA = ctx.chanA(startConnection);
                    var chanB = ctx.chanB("CHAIN_START");
                    ChainChoreography1_A chainChor = new ChainChoreography1_A(
                            new ReactiveSymChannel<>(chanA, chanB),
                            grpcClient
                    );
                    chainChor.chain();
                }
                case "chain3" -> {
                    ChainChoreography3_A chainChor = new ChainChoreography3_A(
                            ctx.chanB("CHAIN_START"),
                            ctx.chanA(nextSidecarConnection),
                            grpcClient
                    );
                    chainChor.chain();
                }
                case "chain5" -> {
                    ChainChoreography5_A chainChor = new ChainChoreography5_A(
                            ctx.chanB("CHAIN_START"),
                            ctx.chanA(nextSidecarConnection),
                            grpcClient
                    );
                    chainChor.chain();
                }
                default -> System.err.println("Unknown choreography name: " + ctx.session);
            }
        });

        return new ChainSidecar(server, "CHAIN_A", nextSidecarConnection, grpcClient, telemetry);
    }

    public static ChainSidecar makeChainB(OpenTelemetry telemetry, String service, String nextSidecarAddress)
            throws Exception {
        var nextSidecarConnection = ClientConnectionManager.makeConnectionManager(nextSidecarAddress, telemetry);

        String[] sidecarSplit = service.split(":");
        var grpcClient = new GrpcClient(sidecarSplit[0], Integer.parseInt(sidecarSplit[1]), telemetry);

        var server = new ReactiveServer("CHAIN_B", telemetry, ctx -> {
            //System.out.println("CHAIN_B received new session");

            switch (ctx.session.choreographyName()) {
                case "chain3" -> {
                    ChainChoreography3_B chainChor = new ChainChoreography3_B(
                            ctx.chanB("CHAIN_A"),
                            ctx.chanA(nextSidecarConnection),
                            grpcClient
                    );
                    chainChor.chain();
                }
                case "chain5" -> {
                    ChainChoreography5_B chainChor = new ChainChoreography5_B(
                            ctx.chanB("CHAIN_A"),
                            ctx.chanA(nextSidecarConnection),
                            grpcClient
                    );
                    chainChor.chain();
                }
                default -> System.err.println("Unknown choreography name: " + ctx.session);
            }
        });

        return new ChainSidecar(server, "CHAIN_B", nextSidecarConnection, grpcClient, telemetry);
    }

    public static ChainSidecar makeChainC(OpenTelemetry telemetry, String sidecar, String nextServiceAddress, String startAddress)
            throws Exception {
        var nextServiceConnection = ClientConnectionManager.makeConnectionManager(nextServiceAddress, telemetry);
        var startConnection = ClientConnectionManager.makeConnectionManager(startAddress, telemetry);

        String[] sidecarSplit = sidecar.split(":");
        var grpcClient = new GrpcClient(sidecarSplit[0], Integer.parseInt(sidecarSplit[1]), telemetry);

        var server = new ReactiveServer("CHAIN_C", telemetry, ctx -> {
            //System.out.println("CHAIN_C received new session");

            switch (ctx.session.choreographyName()) {
                case "chain3" -> {
                    ChainChoreography3_C chainChor = new ChainChoreography3_C(
                            ctx.chanA(startConnection),
                            ctx.chanB("CHAIN_B"),
                            grpcClient
                    );
                    chainChor.chain();
                }
                case "chain5" -> {
                    ChainChoreography5_C chainChor = new ChainChoreography5_C(
                            ctx.chanB("CHAIN_B"),
                            ctx.chanA(nextServiceConnection),
                            grpcClient
                    );
                    chainChor.chain();
                }
                default -> System.err.println("Unknown choreography name: " + ctx.session);
            }
        });

        return new ChainSidecar(server, "CHAIN_C", nextServiceConnection, grpcClient, telemetry);
    }

    public static ChainSidecar makeChainD(OpenTelemetry telemetry, String sidecar, String nextServiceAddress)
            throws Exception {
        var nextServiceConnection = ClientConnectionManager.makeConnectionManager(nextServiceAddress, telemetry);

        String[] sidecarSplit = sidecar.split(":");
        var grpcClient = new GrpcClient(sidecarSplit[0], Integer.parseInt(sidecarSplit[1]), telemetry);

        var server = new ReactiveServer("CHAIN_D", telemetry, ctx -> {
            //System.out.println("CHAIN_C received new session");

            switch (ctx.session.choreographyName()) {
                case "chain5" -> {
                    ChainChoreography5_D chainChor = new ChainChoreography5_D(
                            ctx.chanB("CHAIN_C"),
                            ctx.chanA(nextServiceConnection),
                            grpcClient
                    );
                    chainChor.chain();
                }
                default -> System.err.println("Unknown choreography name: " + ctx.session);
            }
        });

        return new ChainSidecar(server, "CHAIN_D", nextServiceConnection, grpcClient, telemetry);
    }

    public static ChainSidecar makeChainE(OpenTelemetry telemetry, String sidecar, String nextServiceAddress)
            throws Exception {
        var nextServiceConnection = ClientConnectionManager.makeConnectionManager(nextServiceAddress, telemetry);

        String[] sidecarSplit = sidecar.split(":");
        var grpcClient = new GrpcClient(sidecarSplit[0], Integer.parseInt(sidecarSplit[1]), telemetry);

        var server = new ReactiveServer("CHAIN_E", telemetry, ctx -> {
            //System.out.println("CHAIN_C received new session");

            switch (ctx.session.choreographyName()) {
                case "chain5" -> {
                    ChainChoreography5_E chainChor = new ChainChoreography5_E(
                            ctx.chanA(nextServiceConnection),
                            ctx.chanB("CHAIN_D"),
                            grpcClient
                    );
                    chainChor.chain();
                }
                default -> System.err.println("Unknown choreography name: " + ctx.session);
            }
        });

        return new ChainSidecar(server, "CHAIN_E", nextServiceConnection, grpcClient, telemetry);
    }

    public Thread listen() {
        return Thread.ofVirtual()
                .name("REACTIVE_SERVER_" + serviceName)
                .start(() -> {
                    try {
                        server.listen("localhost:8201");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    public void stop() throws Exception {
        server.close();
    }

    public ArrayList<Long> initiateRequestChain(Chain.ChainLength chainLength) throws Exception {
        // Only the chain start sidecar should initiate a request.
        assert serviceName.equals("CHAIN_START");

        System.out.println("Starting request chain");

        var choreographyName = switch (chainLength) {
            case ONE -> "chain1";
            case THREE -> "chain3";
            case FIVE -> "chain5";
            default -> throw new RuntimeException("Invalid chain length: " + chainLength);
        };

        Session session = Session.makeSession(choreographyName, serviceName);
        TelemetrySession telemetrySession = new TelemetrySession(telemetry, session, Span.getInvalid());
        server.registerSession(session, telemetrySession);

        ReactiveClient client = new ReactiveClient(nextSidecarConnection, serviceName, telemetrySession);

        ArrayList<Long> result = switch (chainLength) {
            case ONE -> {
                var chanA = client.chanA(session);
                var chanB = server.chanB(session, "CHAIN_A");
                ChainChoreography1_Start chainChor = new ChainChoreography1_Start(
                        new ReactiveSymChannel<>(chanA, chanB)
                );
                yield chainChor.chain();
            }
            case THREE -> {
                ChainChoreography3_Start chainChor = new ChainChoreography3_Start(
                        client.chanA(session),
                        server.chanB(session, "CHAIN_C")
                );
                yield chainChor.chain();
            }
            case FIVE -> {
                ChainChoreography5_Start chainChor = new ChainChoreography5_Start(
                        client.chanA(session),
                        server.chanB(session, "CHAIN_E")
                );
                yield chainChor.chain();
            }
            default -> throw new RuntimeException("Invalid chain length: " + chainLength);
        };

        System.out.println("GOT RESULT: " + result);

        client.close();

        return result;
    }
}
