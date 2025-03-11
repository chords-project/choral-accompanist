package choral.reactive;

import choral.channels.AsyncDiChannel_B;
import choral.channels.Future;
import choral.lang.Unit;
import choral.reactive.tracing.TelemetrySession;

public class ReactiveChannel_B<M> implements AsyncDiChannel_B<M> {
    private final Session session;
    private final ReactiveReceiver<M> receiver;
    private final TelemetrySession telemetrySession;

    public ReactiveChannel_B(Session session, ReactiveReceiver<M> receiver,
            TelemetrySession telemetrySession) {
        this.session = session;
        this.receiver = receiver;
        this.telemetrySession = telemetrySession;
    }

    @Override
    public <T extends M> Future<T> fcom() {
        return receiver.recv(session);
    }

    @Override
    public <T extends M> Future<T> fcom(Unit unit) {
        return fcom();
    }

    @Override
    public <S extends M> S com(Unit unit) {
        return com();
    }

    @Override
    public <S extends M> S com() {
        return this.<S>fcom().get();
    }

    @Override
    public <T extends Enum<T>> T select(Unit unit) {
        return select();
    }

    @Override
    public <T extends Enum<T>> T select() {
        return receiver.<T>recv_label(session).get();
    }
}
