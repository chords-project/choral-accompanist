package choral.faulttolerance;

import choral.reactive.Session;

import java.sql.SQLException;

public interface FaultDataStore extends AutoCloseable {
    void completeSession(Session session);

    boolean hasSessionCompleted(Session session);

    boolean commitTransaction(Session session, Transaction tx) throws SQLException;

    void compensateTransaction(Session session, Transaction tx) throws SQLException;
}
