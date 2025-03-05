package dev.chords.travel.choreographies;

import choral.reactive.tracing.LgtmConfiguration;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

public class Tracing {
    public static OpenTelemetry initTracing(String serviceName) {

        boolean enableTracing = System.getenv().getOrDefault("ENABLE_TRACING", "").equals("1");
        if (!enableTracing) {
            System.out.println("Tracing disabled");
            return OpenTelemetrySdk.builder().build();
        }

        final String COLLECTOR_ENDPOINT = System.getenv().get("COLLECTOR_SERVICE_ADDR");

        if (COLLECTOR_ENDPOINT == null) {
            System.out.println("Tracing enabled, but no COLLECTOR_SERVICE_ADDR was provided");
            return OpenTelemetrySdk.builder().build();
        }

        System.out.println("Configuring choreographic telemetry to: " + COLLECTOR_ENDPOINT);
        return LgtmConfiguration.initTelemetry(COLLECTOR_ENDPOINT, serviceName);
    }
}
