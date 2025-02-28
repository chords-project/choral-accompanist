package choral.reactive;

import choral.channels.AsyncDiChannel_A;
import choral.channels.DiChannel_A;
import choral.lang.Unit;
import choral.reactive.tracing.TelemetrySession;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

public class ReactiveChannel_A<M> implements AsyncDiChannel_A<M> {

    public final Session session;
    private final ReactiveSender<M> sender;
    private final TelemetrySession telemetrySession;

    public ReactiveChannel_A(Session session, ReactiveSender<M> sender, TelemetrySession telemetrySession) {
        this.session = session;
        this.sender = sender;
        this.telemetrySession = telemetrySession;
    }

    @Override
    public <T extends M> Unit fcom(T msg) {

        // Associates each message with the session
        sender.send(session, msg);

        return Unit.id;
    }

    @Override
    public <S extends M> Unit com(S s) {
        return fcom(s);
    }

    @Override
    public <T extends Enum<T>> Unit select(T t) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

//    @SuppressWarnings("unchecked")
//    @Override
//    public <T extends Enum<T>> Unit select(T msg) {
//        Span span = telemetrySession.tracer.spanBuilder("ReactiveChannel send message")
//                .setAttribute("channel.session", session.toString())
//                .setAttribute("channel.message", msg.toString())
//                .setAttribute("channel.sender", sender.toString())
//                .startSpan();
//
//        try (Scope scope = span.makeCurrent()) {
//            sender.send(session, (M) msg);
//        }
//
//        span.end();
//
//        return Unit.id;
//    }
}
