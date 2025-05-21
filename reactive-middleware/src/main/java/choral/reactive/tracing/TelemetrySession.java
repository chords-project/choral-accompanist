package choral.reactive.tracing;

import choral.reactive.Session;
import choral.reactive.connection.Message;
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

public class TelemetrySession {

    private final OpenTelemetry telemetry;
    public final Tracer tracer;
    public final Meter meter;
    public final Logger logger;

    public final Session session;

    private Span choreographySpan = null;

    private Context choreographyContext;
    private SpanContext senderLinkContext;

    public static TelemetrySession makeNoop(Session session) {
        return new TelemetrySession(OpenTelemetry.noop(), session, Span.getInvalid());
    }

    public TelemetrySession(OpenTelemetry telemetry, Message msg) {
        this.telemetry = telemetry;
        this.session = msg.session;

        this.tracer = this.telemetry.getTracer(JaegerConfiguration.TRACER_NAME);
        this.meter = this.telemetry.getMeter(JaegerConfiguration.TRACER_NAME);
        this.logger = this.telemetry.getLogsBridge().get(JaegerConfiguration.TRACER_NAME);

        this.senderLinkContext = msg.senderSpanContext.toSpanContext();
        this.choreographyContext = telemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.root(), msg, new HeaderTextMapGetter());
    }

    // Configure initial telemetry session
    public TelemetrySession(OpenTelemetry telemetry, Session session, Span span) {
        this.telemetry = telemetry;
        this.session = session;

        this.senderLinkContext = null;
        this.choreographyContext = Context.root().with(span);
        this.choreographySpan = span;

        this.tracer = this.telemetry.getTracer(JaegerConfiguration.TRACER_NAME);
        this.meter = this.telemetry.getMeter(JaegerConfiguration.TRACER_NAME);
        this.logger = this.telemetry.getLogsBridge().get(JaegerConfiguration.TRACER_NAME);
    }

    // Configure dummy telemetry session
    public TelemetrySession(Session session) {
        this(OpenTelemetry.noop(), session, Span.getInvalid());
    }

    public Span makeChoreographySpan() {
        if (this.choreographySpan != null)
            throw new RuntimeException("TelemetrySession::makeChoreographySpan may only be called once");

        this.choreographySpan = tracer.spanBuilder("choreography session")
                .setParent(choreographyContext)
                .addLink(senderLinkContext)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("choreography.session", session.toString())
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
        Attributes extraAttributes = Attributes.builder().put("session", session.toString()).putAll(attributes).build();

        System.out.println(message + ": " + attributesToString(extraAttributes));
        //choreographySpan.addEvent(message, extraAttributes);

        logger.logRecordBuilder()
                .setAllAttributes(extraAttributes)
                .setBody(message)
                .setSeverity(severity)
                .emit();
    }

    public void recordException(String message, Exception e, boolean error, Attributes attributes) {
        Attributes extraAttributes = Attributes.builder()
                .put("session", session.toString()).put("message", message).putAll(attributes).build();

        if (error)
            choreographySpan.setAttribute("error", true);
        choreographySpan.recordException(e, extraAttributes);

        log(
                Severity.ERROR,
                message,
                extraAttributes.toBuilder()
                        .putAll(choral.reactive.tracing.Logger.exceptionAttributes(e))
                        .build()
        );
    }

    public void recordException(String message, Exception e, boolean error) {
        this.recordException(message, e, error, Attributes.empty());
    }

    public void injectSessionContext(Message msg) {
        telemetry.getPropagators()
                .getTextMapPropagator()
                .inject(choreographyContext, msg, new HeaderTextMapSetter());

        msg.senderSpanContext = new Message.SerializedSpanContext(choreographySpan.getSpanContext());
    }

    private String attributesToString(Attributes attributes) {
        return String.join(", ",
                attributes.asMap().entrySet()
                        .stream()
                        .map(entry -> entry.getKey().toString() + "=" + entry.getValue().toString())
                        .toList());
    }

    // @Override
    // public void close() throws IOException {
    // choreographySpan.end();
    // }
}
