package choral.reactive;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import choral.channels.Future;
import choral.reactive.tracing.TelemetrySession;
import io.opentelemetry.api.OpenTelemetry;

public class LocalReactiveQueue implements ReactiveSender<Object>, ReactiveReceiver<Object> {

    public interface NewSessionEvent {
        void onNewSession(Session session);
    }

    private final MessageQueue<Object> msgQueue = new MessageQueue<>(OpenTelemetry.noop());
    private NewSessionEvent newSessionEvent = null;
    private final HashMap<Session, Integer> nextSequenceNumber = new HashMap<>();

    public LocalReactiveQueue() {
    }

    public void onNewSession(NewSessionEvent event) {
        this.newSessionEvent = event;
    }

    @Override
    public void send(Session session, Object msg) {
        nextSequenceNumber.put(session, nextSequenceNumber.getOrDefault(session, 0) + 1);
        int sequenceNumber = nextSequenceNumber.get(session);
        msgQueue.addMessage(session, msg, sequenceNumber, TelemetrySession.makeNoop(session));
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Future<T> recv(Session session) {
        var future = this.msgQueue.retrieveMessage(session, TelemetrySession.makeNoop(session));

        return () -> {
            try {
                return (T) future.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    @Override
    public ReactiveChannel_A<Object> chanA(Session session) {
        return new ReactiveChannel_A<>(session, this, TelemetrySession.makeNoop(session));
    }

    public ReactiveChannel_B<Object> chanB(Session session) {
        return new ReactiveChannel_B<>(session, this, TelemetrySession.makeNoop(session));
    }
}
