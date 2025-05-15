package choral.reactive;

import choral.channels.AsyncDiChannel_A;
import choral.channels.DiChannel_A;
import choral.lang.Unit;
import choral.reactive.tracing.TelemetrySession;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.io.IOException;
import java.io.Serializable;

public class ReactiveChannel_A<M> implements AsyncDiChannel_A<M> {

    public final Session session;
    private final ReactiveSender<M> sender;
    private final TelemetrySession telemetrySession;

    public ReactiveChannel_A(Session session, ReactiveSender<M> sender, TelemetrySession telemetrySession) {
        this.session = session;
        this.sender = sender;
        this.telemetrySession = telemetrySession;
    }

    public static ReactiveChannel_A<Serializable> connect(SessionContext ctx, Unit a, Unit b, String serverAddressEnv) {
        return connect(ctx, serverAddressEnv);
    }

    public static ReactiveChannel_A<Serializable> connect(SessionContext ctx, String serverAddressEnv) {
        try {
            return ctx.chanA(serverAddressEnv);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
    public <T extends Enum<T>> Unit select(T label) {

        // Associates each label with the session
        sender.select(session, label);

        return Unit.id;
    }
}
