package choral.reactive;

public interface ReactiveSender<M> {
    void send(Session session, M msg);
    <T extends Enum<T>> void select(Session session, T label);

    ReactiveChannel_A<M> chanA(Session session);
}
