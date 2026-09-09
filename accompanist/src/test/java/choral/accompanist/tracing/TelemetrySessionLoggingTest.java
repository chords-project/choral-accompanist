package choral.accompanist.tracing;

import choral.accompanist.Session;
import choral.accompanist.ReactiveServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetrySessionLoggingTest {

    @Test
    void logUsesSessionSpanContextOutsideThreadLocalScope() {
        var exporter = new CollectingLogExporter();
        var loggerProvider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build();
        var tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .build();
        var telemetry = OpenTelemetrySdk.builder()
                .setLoggerProvider(loggerProvider)
                .setTracerProvider(tracerProvider)
                .build();

        try {
            var session = new Session("test choreography", "sender", 42, "run-1");
            var telemetrySession = TelemetrySession.createRoot(telemetry, session);
            var sessionSpan = telemetrySession.getChoreographySpan();

            // There deliberately is no makeCurrent() scope here. This mirrors logging
            // from recovery and virtual-thread callbacks in the fault-tolerance extension.
            telemetrySession.log("session event");

            assertEquals(1, exporter.records.size());
            var record = exporter.records.getFirst();
            assertEquals(42L, record.getAttributes().get(AttributeKey.longKey("choreography.session_id")));
            assertTrue(record.getSpanContext().isValid());
            assertEquals(sessionSpan.getSpanContext().getTraceId(), record.getSpanContext().getTraceId());
            assertEquals(sessionSpan.getSpanContext().getSpanId(), record.getSpanContext().getSpanId());
        } finally {
            loggerProvider.close();
            tracerProvider.close();
        }
    }

    @Test
    void rethrownSessionExceptionIsLoggedExactlyOnceAtExecutionBoundary() {
        var exporter = new CollectingLogExporter();
        var loggerProvider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build();
        var tracerProvider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).build();
        var telemetry = OpenTelemetrySdk.builder()
                .setLoggerProvider(loggerProvider)
                .setTracerProvider(tracerProvider)
                .build();

        try {
            var session = new Session("test choreography", "sender", 43, "run-2");
            var telemetrySession = TelemetrySession.createRoot(telemetry, session);
            var traceId = telemetrySession.getChoreographySpan().getSpanContext().getTraceId();
            var server = new ReactiveServer("test service", telemetry, ignored -> {
                throw new IllegalStateException("choreography failed");
            });

            assertThrows(IllegalStateException.class, () -> server.invokeManualSession(telemetrySession));

            var errors = exporter.records.stream()
                    .filter(record -> record.getSeverity() == Severity.ERROR)
                    .toList();
            assertEquals(1, errors.size());
            assertEquals(43L, errors.getFirst().getAttributes()
                    .get(AttributeKey.longKey("choreography.session_id")));
            assertEquals(traceId, errors.getFirst().getSpanContext().getTraceId());
        } finally {
            loggerProvider.close();
            tracerProvider.close();
        }
    }

    private static final class CollectingLogExporter implements LogRecordExporter {
        private final List<LogRecordData> records = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<LogRecordData> logs) {
            records.addAll(logs);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
