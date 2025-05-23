package choral.faulttolerance;

import choral.reactive.Session;

public interface FaultDataStore extends AutoCloseable {
    void completeSession(Session session);

    boolean hasSessionCompleted(Session session);
}
