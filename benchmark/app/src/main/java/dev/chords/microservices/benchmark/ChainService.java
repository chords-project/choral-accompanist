package dev.chords.microservices.benchmark;

import choral.reactive.ReactiveClient;
import choral.reactive.ReactiveServer;
import choral.reactive.Session;
import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.TelemetrySession;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;

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

    public static ChainService makeForwarder(OpenTelemetry telemetry, String serviceName, String nextServiceAddress)
            throws Exception {
        var nextServiceConnection = ClientConnectionManager.makeConnectionManager(nextServiceAddress, telemetry);

        var grpcClient = new GrpcClient(5430, telemetry);

        var server = new ReactiveServer(serviceName, telemetry, ctx -> {
            System.out.println(serviceName + " received new session");

            switch (ctx.session.choreographyName()) {
                case "chain":
                    ChainChoreography_B chorRcv = new ChainChoreography_B(ctx.chanB(serviceName), grpcClient);
                    String value = chorRcv.forward();

                    ChainChoreography_A chorFwd = new ChainChoreography_A(ctx.chanA(nextServiceConnection));
                    chorFwd.forward(value);
                default:
                    System.err.println("Unknown choreography name: " + ctx.session);
            }
        });

        return new ChainService(server, serviceName, nextServiceConnection, grpcClient, telemetry);
    }

    public static ChainService makeInitiator(OpenTelemetry telemetry, String serviceName, String nextServiceAddress)
            throws Exception {
        var nextServiceConnection = ClientConnectionManager.makeConnectionManager(nextServiceAddress, telemetry);

        var grpcClient = new GrpcClient(5430, telemetry);

        var server = new ReactiveServer(serviceName, telemetry, ctx -> {
            System.out.println(serviceName + " received new session");
        });

        return new ChainService(server, serviceName, nextServiceConnection, grpcClient, telemetry);
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

        Session session = Session.makeSession("chain", serviceName);
        TelemetrySession telemetrySession = new TelemetrySession(telemetry, session, Span.getInvalid());
        ReactiveClient client = new ReactiveClient(nextServiceConnection, serviceName, telemetrySession);

        ChainChoreography_A chorFwd = new ChainChoreography_A(client.chanA(session));
        chorFwd.forward("start");

        client.close();
    }
}
