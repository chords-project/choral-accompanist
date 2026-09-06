package choral.accompanist.tracing;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.time.Duration;

public class LgtmConfiguration {

    public static final String DEFAULT_ENDPOINT = "http://localhost:4317";
    public static final Duration DEFAULT_METRIC_EXPORT_INTERVAL = Duration.ofSeconds(5);

    public static OpenTelemetrySdk initTelemetry(String endpoint, String serviceName) {
        long intervalSeconds = parsePositiveLong(
                System.getenv("OTEL_METRIC_EXPORT_INTERVAL_SECONDS"),
                DEFAULT_METRIC_EXPORT_INTERVAL.toSeconds());
        return initTelemetry(endpoint, serviceName, Duration.ofSeconds(intervalSeconds));
    }

    public static OpenTelemetrySdk initTelemetry(String endpoint, String serviceName, Duration metricExportInterval) {
        String instanceId = System.getenv().getOrDefault("OTEL_SERVICE_INSTANCE_ID",
                System.getenv().getOrDefault("HOSTNAME", "local"));

        Resource resource = Resource.getDefault().toBuilder()
                .put("service.name", serviceName)
                .put("service.instance.id", instanceId)
                .put("service.version", AccompanistTelemetry.IMPLEMENTATION_VERSION)
                .put("service.namespace", "choral-accompanist")
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(
                        BatchSpanProcessor.builder(
                                OtlpGrpcSpanExporter.builder()
                                        .setEndpoint(endpoint)
                                        .setTimeout(Duration.ofSeconds(10))
                                        .build()
                        ).build()
                )
                .setSampler(Sampler.parentBased(Sampler.alwaysOn()))
                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(
                        PeriodicMetricReader.builder(
                                OtlpGrpcMetricExporter.builder()
                                        .setEndpoint(endpoint)
                                        .setTimeout(Duration.ofSeconds(30))
                                        .build()
                        ).setInterval(metricExportInterval).build()
                )
                .build();

        SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(
                        BatchLogRecordProcessor.builder(
                                        OtlpGrpcLogRecordExporter.builder()
                                                .setEndpoint(endpoint)
                                                .setTimeout(Duration.ofSeconds(30))
                                                .build()
                                )
                                .build()
                )
                .build();

        ContextPropagators propagators = ContextPropagators.create(
                TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())
        );

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .setLoggerProvider(loggerProvider)
                .setPropagators(propagators)
                .build();
    }

    private static long parsePositiveLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
