package choral.accompanist.tracing;

/** Shared OpenTelemetry instrumentation identity for Accompanist. */
public final class AccompanistTelemetry {
    public static final String INSTRUMENTATION_SCOPE_NAME = "choral.accompanist";
    public static final String IMPLEMENTATION_VERSION = "0.1.0";

    private AccompanistTelemetry() {
    }
}
