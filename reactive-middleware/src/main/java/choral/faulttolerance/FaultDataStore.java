package choral.faulttolerance;

import choral.reactive.Session;

import java.sql.SQLException;

public interface FaultDataStore extends AutoCloseable {
    void startSession(Session session) throws SQLException;

    void completeSession(Session session) throws SQLException;

    void failSession(Session session) throws SQLException;

    boolean hasSessionCompleted(Session session) throws SQLException;

    boolean commitTransaction(Session session, Transaction tx) throws SQLException;

    void compensateTransaction(Session session, Transaction tx) throws SQLException;
}
