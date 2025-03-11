package choral.reactive;

import choral.channels.Future;

public interface ReactiveReceiver<M> {
    /**
     * Registers for the next message to arrive for the given Session, and returns a Future for the result.
     */
    <T extends M> Future<T> recv(Session session);

    <T extends Enum<T>> Future<T> recv_label(Session session);
}
