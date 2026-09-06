package choral.accompanist.tracing;

import choral.accompanist.Session;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;

/**
 * Fault tolerance event metrics.
 */
public final class FaultToleranceTelemetry {
    private final String service;
    private final LongCounter attempts, completions, failures, restartRequests, sendAttempts, sendFailures, confirmations;

    public FaultToleranceTelemetry(OpenTelemetry telemetry, String service) {
        this.service = service.toLowerCase();
        var meter = telemetry.getMeter(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME);
        attempts = meter.counterBuilder("accompanist.choreography.attempts").setUnit("{attempt}").build();
        completions = meter.counterBuilder("accompanist.choreography.completions").setUnit("{completion}").build();
        failures = meter.counterBuilder("accompanist.choreography.failures").setUnit("{failure}").build();
        restartRequests = meter.counterBuilder("accompanist.choreography.restart_requests").setUnit("{request}").build();
        sendAttempts = meter.counterBuilder("accompanist.message.send_attempts").setUnit("{send}").build();
        sendFailures = meter.counterBuilder("accompanist.message.send_failures").setUnit("{failure}").build();
        confirmations = meter.counterBuilder("accompanist.message.delivery_confirmations").setUnit("{confirmation}").build();
    }

    private Attributes attrs(Session session, String extraKey, String extraValue) {
        var builder = metricAttributes(session, service).toBuilder();
        if (session.benchmarkRunId() != null) builder.put("benchmark.run_id", session.benchmarkRunId());
        if (extraKey != null) builder.put(extraKey, extraValue);
        return builder.build();
    }

    /**
     * Safe for metrics: deliberately excludes session, trace, and request identifiers.
     */
    public static Attributes metricAttributes(Session session, String service) {
        var builder = Attributes.builder().put("service", service.toLowerCase()).put("choreography", session.choreographyName());
        if (session.benchmarkRunId() != null) builder.put("benchmark.run_id", session.benchmarkRunId());
        return builder.build();
    }

    public void attempt(Session s, String cause) {
        attempts.add(1, attrs(s, "cause", cause));
    }

    public void completion(Session s) {
        completions.add(1, attrs(s, null, null));
    }

    public void failure(Session s, String cause) {
        failures.add(1, attrs(s, "cause", cause));
    }

    public void restart(Session s, String cause) {
        restartRequests.add(1, attrs(s, "cause", cause));
    }

    public void sendAttempt(Session s, String destination, String classification) {
        sendAttempts.add(1, attrs(s, "destination", destination + ":" + classification));
    }

    public void sendFailure(Session s, String category) {
        sendFailures.add(1, attrs(s, "error.category", category));
    }

    public void confirmation(Session s) {
        confirmations.add(1, attrs(s, null, null));
    }
}
