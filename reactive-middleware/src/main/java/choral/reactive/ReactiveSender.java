package choral.reactive;

public interface ReactiveSender<M> {
    void send(Session session, M msg);

    ReactiveChannel_A<M> chanA(Session session);
}
