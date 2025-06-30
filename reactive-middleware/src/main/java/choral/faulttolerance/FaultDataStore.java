package choral.faulttolerance;

import choral.reactive.Session;

import java.sql.SQLException;
import java.util.List;

public interface FaultDataStore extends AutoCloseable {
    void startSession(Session session) throws SQLException;

    void completeSession(int sessionID) throws SQLException;

    void failSession(int sessionID) throws SQLException;

    boolean hasSessionCompleted(int sessionID) throws SQLException;

    boolean commitTransaction(int sessionID, Transaction tx) throws SQLException;

    void compensateTransactions(int sessionID) throws SQLException;

    List<Session> recoverStartedSessions() throws SQLException;
}
