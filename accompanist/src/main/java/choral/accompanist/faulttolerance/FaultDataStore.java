package choral.accompanist.faulttolerance;

import choral.accompanist.Session;

import java.sql.SQLException;
import java.util.List;

/**
 * Interface needed to keep track of the state of a fault-tolerant execution.
 * Mainly used by {@link FaultTolerantServer}.
 */
public interface FaultDataStore extends AutoCloseable {
    /** @return true only when this call starts a new local attempt. */
    boolean startSession(Session session) throws SQLException;

    /** @return true only when the durable state changed to completed. */
    boolean completeSession(int sessionID) throws SQLException;

    /** @return true only when the durable state changed to failed. */
    boolean failSession(Session session) throws SQLException;

    /** @return true only when the durable state changed to restart. */
    boolean restartSession(int sessionID) throws SQLException;

    boolean hasSessionCompleted(int sessionID) throws SQLException;

    boolean commitTransaction(int sessionID, Transaction tx) throws SQLException;

    void compensateTransactions(int sessionID) throws SQLException;

    List<Session> recoverStartedSessions() throws SQLException;
}
