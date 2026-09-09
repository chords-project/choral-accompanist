package choral.accompanist.tracing;

import choral.accompanist.Session;
import choral.accompanist.connection.Message;
import choral_reactive.ChannelOuterClass;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.util.Map;

public class TelemetrySession implements AutoCloseable {

    public enum AttemptKind {
        NEW("new"), RECOVERY("recovery");

        private final String metricValue;

        AttemptKind(String metricValue) {
            this.metricValue = metricValue;
        }

        public String metricValue() {
            return metricValue;
        }
    }

    private final OpenTelemetry telemetry;
    public final Tracer tracer;
    public final Meter meter;
    public final Logger logger;

    public final Session session;
    private final AttemptKind attemptKind;
    private final boolean rootSpan;

    private Span choreographySpan = null;
    private boolean closed;

    private Context choreographyContext;
    private SpanContext senderLinkContext;

    public static TelemetrySession makeNoop(Session session) {
        return new TelemetrySession(OpenTelemetry.noop(), session, Span.getInvalid());
    }

    public TelemetrySession(OpenTelemetry telemetry, Message msg) {
        this.telemetry = telemetry;
        this.session = msg.session;
        this.attemptKind = AttemptKind.NEW;

        this.tracer = this.telemetry.getTracer(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME);
        this.meter = this.telemetry.getMeter(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME);
        this.logger = this.telemetry.getLogsBridge().get(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME);

        this.senderLinkContext = msg.senderSpanContext.toSpanContext();
        this.choreographyContext = telemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.root(), msg, new HeaderTextMapGetter());
        this.rootSpan = !Span.fromContext(choreographyContext).getSpanContext().isValid();
    }

    // Configure initial telemetry session
    public TelemetrySession(OpenTelemetry telemetry, Session session, Span span) {
        this(telemetry, session, span, AttemptKind.NEW);
    }

    public TelemetrySession(OpenTelemetry telemetry, Session session, Span span, AttemptKind attemptKind) {
        this(telemetry, session, span, attemptKind, true);
    }

    public TelemetrySession(OpenTelemetry telemetry, Session session, Span span, AttemptKind attemptKind,
                            boolean rootSpan) {
        this.telemetry = telemetry;
        this.session = session;
        this.attemptKind = attemptKind;
        this.rootSpan = rootSpan;

        this.senderLinkContext = null;
        this.choreographyContext = Context.root().with(span);
        this.choreographySpan = span;

        this.tracer = this.telemetry.getTracer(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME);
        this.meter = this.telemetry.getMeter(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME);
        this.logger = this.telemetry.getLogsBridge().get(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME);
    }

    // Configure dummy telemetry session
    public TelemetrySession(Session session) {
        this(OpenTelemetry.noop(), session, Span.getInvalid());
    }

    /**
     * Creates a telemetry-backed session with a valid root span for a manual invocation.
     */
    public static TelemetrySession createRoot(OpenTelemetry telemetry, Session session) {
        Span rootSpan = telemetry.getTracer(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME)
                .spanBuilder("choreography session")
                .setNoParent()
                .setSpanKind(SpanKind.SERVER)
                .setAllAttributes(commonAttributes(session))
                .startSpan();
        return new TelemetrySession(telemetry, session, rootSpan);
    }

    /**
     * Creates a session from the transport envelope without deserializing its payload.
     * Used to correlate failures that happen while decoding the payload itself.
     */
    public static TelemetrySession fromGrpcEnvelope(OpenTelemetry telemetry, ChannelOuterClass.Message message) {
        var session = new Session(
                message.getChoreography(), message.getSender(), message.getSessionId(),
                message.getBenchmarkRunId().isBlank() ? null : message.getBenchmarkRunId());
        Context parentContext = telemetry.getPropagators().getTextMapPropagator().extract(
                Context.root(), message.getHeadersMap(), MapTextMapGetter.INSTANCE);
        var senderContext = SpanContext.getInvalid();
        try {
            senderContext = new Message.SerializedSpanContext(message.getSpanContext()).toSpanContext();
        } catch (RuntimeException ignored) {
            // A malformed sender context must not hide the payload decoding failure.
        }

        var spanBuilder = telemetry.getTracer(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME)
                .spanBuilder("incoming mailbox message")
                .setSpanKind(SpanKind.SERVER)
                .setAllAttributes(commonAttributes(session));
        boolean hasParent = Span.fromContext(parentContext).getSpanContext().isValid();
        if (hasParent) spanBuilder.setParent(parentContext);
        else spanBuilder.setNoParent();
        if (senderContext.isValid()) spanBuilder.addLink(senderContext);
        return new TelemetrySession(telemetry, session, spanBuilder.startSpan(), AttemptKind.NEW, !hasParent);
    }

    /**
     * Trace/log attributes; session id is intentionally excluded from metric attributes.
     */
    public static Attributes commonAttributes(Session session) {
        var builder = Attributes.builder().put("choreography.name", session.choreographyName())
                .put("choreography.session_id", session.sessionID());
        if (session.benchmarkRunId() != null) builder.put("benchmark.run_id", session.benchmarkRunId());
        return builder.build();
    }

    public synchronized Span getChoreographySpan() {
        if (this.choreographySpan != null)
            return this.choreographySpan;
        if (closed) return Span.getInvalid();

        this.choreographySpan = tracer.spanBuilder("choreography session")
                .setParent(choreographyContext)
                .addLink(senderLinkContext == null ? SpanContext.getInvalid() : senderLinkContext)
                .setSpanKind(SpanKind.SERVER)
                .setAllAttributes(commonAttributes(session))
                .startSpan();

        return this.choreographySpan;
    }

    public void log(String message) {
        this.log(message, Attributes.empty());
    }

    public void log(String message, Attributes attributes) {
        this.log(Severity.INFO, message, attributes);
    }

    public void log(Severity severity, String message, Attributes attributes) {
        Attributes extraAttributes = Attributes.builder().put("session", session.toString()).putAll(commonAttributes(session)).putAll(attributes).build();

        logger.logRecordBuilder()
                .setContext(Context.root().with(getChoreographySpan()))
                .setAllAttributes(extraAttributes)
                .setBody(message)
                .setSeverity(severity)
                .emit();
    }

    public void recordException(String message, Exception e, boolean error, Attributes attributes) {
        Span span = getChoreographySpan();
        Attributes extraAttributes = Attributes.builder()
                .put("session", session.toString()).put("message", message).putAll(attributes).build();

        if (error)
            span.setAttribute("error", true);
        span.recordException(e, extraAttributes);

        log(
                Severity.ERROR,
                message,
                extraAttributes.toBuilder()
                        .putAll(choral.accompanist.tracing.Logger.exceptionAttributes(e))
                        .build()
        );
    }

    public void recordException(String message, Exception e, boolean error) {
        this.recordException(message, e, error, Attributes.empty());
    }

    public void injectSessionContext(Message msg) {
        Span span = getChoreographySpan();
        Context outgoingContext = Context.root().with(span);
        telemetry.getPropagators()
                .getTextMapPropagator()
                .inject(outgoingContext, msg, new HeaderTextMapSetter());

        msg.senderSpanContext = new Message.SerializedSpanContext(span.getSpanContext());
    }

    public SpanContext spanContext() {
        return getChoreographySpan().getSpanContext();
    }

    public AttemptKind attemptKind() {
        return attemptKind;
    }

    /**
     * Whether the choreography span is the root of its distributed trace.
     */
    public boolean isRootSpan() {
        return rootSpan;
    }

    /** Ends this session's choreography span, if it has been created. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (choreographySpan != null) choreographySpan.end();
    }

    private enum MapTextMapGetter implements TextMapGetter<Map<String, String>> {
        INSTANCE;

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    }
}
