package dev.chords.microservices.benchmark;

import choral.reactive.ReactiveClient;
import choral.reactive.ReactiveServer;
import choral.reactive.Session;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.TelemetrySession;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;

import java.util.ArrayList;

public class ChainService {
    private ReactiveServer server;
    private String serviceName;
    private ClientConnectionManager nextServiceConnection;
    private GrpcClient grpcClient;
    private OpenTelemetry telemetry;

    private ChainService(ReactiveServer server, String serviceName, ClientConnectionManager nextServiceConnection,
            GrpcClient grpcClient, OpenTelemetry telemetry) {
        this.server = server;
        this.serviceName = serviceName;
        this.nextServiceConnection = nextServiceConnection;
        this.grpcClient = grpcClient;
        this.telemetry = telemetry;
    }

    public static ChainService makeChainA(OpenTelemetry telemetry, String sidecar, String nextServiceAddress)
            throws Exception {
        var nextServiceConnection = ClientConnectionManager.makeConnectionManager(nextServiceAddress + ":8201", telemetry);

        var grpcClient = new GrpcClient(sidecar, 5430, telemetry);

        var server = new ReactiveServer("CHAIN_A", telemetry, ctx -> {
            System.out.println("CHAIN_A received new session");
        });

        return new ChainService(server, "CHAIN_A", nextServiceConnection, grpcClient, telemetry);
    }

    public static ChainService makeChainB(OpenTelemetry telemetry, String sidecar, String nextServiceAddress)
            throws Exception {
        var nextServiceConnection = ClientConnectionManager.makeConnectionManager(nextServiceAddress + ":8201", telemetry);

        var grpcClient = new GrpcClient(sidecar, 5430, telemetry);

        var server = new ReactiveServer("CHAIN_B", telemetry, ctx -> {
            System.out.println("CHAIN_B received new session");

            switch (ctx.session.choreographyName()) {
                case "chain":
                    ChainChoreography_B chainChor = new ChainChoreography_B(
                            ctx.chanB("CHAIN_A"),
                            ctx.chanA(nextServiceConnection),
                            grpcClient
                    );
                    chainChor.chain();
                default:
                    System.err.println("Unknown choreography name: " + ctx.session);
            }
        });

        return new ChainService(server, "CHAIN_B", nextServiceConnection, grpcClient, telemetry);
    }

    public static ChainService makeChainC(OpenTelemetry telemetry, String sidecar, String nextServiceAddress)
            throws Exception {
        var nextServiceConnection = ClientConnectionManager.makeConnectionManager(nextServiceAddress + ":8201", telemetry);

        var grpcClient = new GrpcClient(sidecar, 5430, telemetry);

        var server = new ReactiveServer("CHAIN_C", telemetry, ctx -> {
            System.out.println("CHAIN_C received new session");

            switch (ctx.session.choreographyName()) {
                case "chain":
                    ChainChoreography_C chainChor = new ChainChoreography_C(
                            ctx.chanB("CHAIN_B"),
                            ctx.chanA(nextServiceConnection),
                            grpcClient
                    );
                    chainChor.chain();
                default:
                    System.err.println("Unknown choreography name: " + ctx.session);
            }
        });

        return new ChainService(server, "CHAIN_C", nextServiceConnection, grpcClient, telemetry);
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

    public void initiateRequestChain() throws Exception {

        System.out.println("Starting request chain");

        Session session = Session.makeSession("chain", serviceName);
        TelemetrySession telemetrySession = new TelemetrySession(telemetry, session, Span.getInvalid());
        server.registerSession(session, telemetrySession);

        ReactiveClient client = new ReactiveClient(nextServiceConnection, serviceName, telemetrySession);


        ChainChoreography_A chainChor = new ChainChoreography_A(
                client.chanA(session),
                server.chanB(session, "CHAIN_C"),
                grpcClient
        );
        ArrayList<Long> result = chainChor.chain();
        System.out.println("GOT RESULT: " + result);

        client.close();
    }
}
