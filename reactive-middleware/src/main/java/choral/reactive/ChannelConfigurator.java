package choral.reactive;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.opentelemetry.GrpcOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ChannelConfigurator {
    public static ManagedChannel makeChannel(InetSocketAddress address, OpenTelemetry telemetry) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(address.getHostName(), address.getPort())
                .usePlaintext()
                .keepAliveTime(5, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                //.directExecutor();
                //.executor(Executors.newCachedThreadPool());
                .executor(Executors.newVirtualThreadPerTaskExecutor());

        // Add metric instrumentation
        GrpcOpenTelemetry grpcOpenTelmetry = GrpcOpenTelemetry.newBuilder().sdk(telemetry).build();
        grpcOpenTelmetry.configureChannelBuilder(builder);

        // Add context propagation
        GrpcTelemetry grpcTelemetry = GrpcTelemetry.create(telemetry);
        builder.intercept(grpcTelemetry.newClientInterceptor());

        return builder.build();
    }
}
