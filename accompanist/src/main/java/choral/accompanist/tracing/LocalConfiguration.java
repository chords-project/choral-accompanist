package choral.accompanist.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.exporter.logging.SystemOutLogRecordExporter;

public class LocalConfiguration {

    /**
     * A telemetry instance that logs to stdout
     */
    public static OpenTelemetry initTelemetry(String serviceName) {
        Resource resource = Resource.getDefault().toBuilder()
                .put("service.name", serviceName)
                .build();

        // Create an exporter that writes to stdout
        LogRecordExporter stdoutExporter = SystemOutLogRecordExporter.create();

        // Build the logger provider with a processor that uses the exporter
        SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(
                        BatchLogRecordProcessor.builder(stdoutExporter).build()
                )
                .build();

        return OpenTelemetrySdk.builder()
                .setLoggerProvider(loggerProvider)
                .build();
    }
}
