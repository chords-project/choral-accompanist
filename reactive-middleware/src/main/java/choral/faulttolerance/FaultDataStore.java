package choral.faulttolerance;

import java.sql.SQLException;

public interface FaultDataStore extends AutoCloseable {
    void startSession(int sessionID) throws SQLException;

    void completeSession(int sessionID) throws SQLException;

    void failSession(int sessionID) throws SQLException;

    boolean hasSessionCompleted(int sessionID) throws SQLException;

    boolean commitTransaction(int sessionID, Transaction tx) throws SQLException;

    void compensateTransactions(int sessionID) throws SQLException;
}
