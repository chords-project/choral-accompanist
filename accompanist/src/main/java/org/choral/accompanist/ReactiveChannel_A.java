package org.choral.accompanist;

import org.choral.accompanist.channels.AsyncDiChannel_A;
import org.choral.accompanist.faulttolerance.FaultSessionContext;
import choral.lang.Unit;
import org.choral.accompanist.tracing.TelemetrySession;

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

    public static ReactiveChannel_A<Serializable> connect(SessionContext ctx, String serverAddress) {
        try {
            return ctx.chanA(serverAddress);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static ReactiveChannel_A<Serializable> connect(
            FaultSessionContext senderCtx, Unit senderName,
            Unit receiverCtx, String receiverName) {
        return connect(senderCtx, receiverName);
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
