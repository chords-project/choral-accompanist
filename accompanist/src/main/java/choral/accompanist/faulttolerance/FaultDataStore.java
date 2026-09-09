package choral.accompanist.faulttolerance;

import choral.accompanist.Session;
import io.opentelemetry.api.trace.SpanContext;

import java.sql.SQLException;
import java.util.List;

/**
 * Interface needed to keep track of the state of a fault-tolerant execution.
 * Mainly used by {@link FaultTolerantServer}.
 */
public interface FaultDataStore extends AutoCloseable {
    /**
     * @return true only when this call starts a new local attempt.
     */
    boolean startSession(Session session) throws SQLException;

    /** Starts an attempt and durably records its original distributed-trace context. */
    default boolean startSession(Session session, SpanContext traceContext) throws SQLException {
        return startSession(session);
    }

    /**
     * @return true only when the durable state changed to completed.
     */
    boolean completeSession(int sessionID) throws SQLException;

    /**
     * @return true only when the durable state changed to failed.
     */
    boolean failSession(Session session) throws SQLException;

    /**
     * @return true only when the durable state changed to restart.
     */
    boolean restartSession(int sessionID) throws SQLException;

    /**
     * Marks the session for restart in the durable store.
     * if session failure was caused by a receive timeout, the `waitingSender` and `waitingSequence` marks
     * which sender and message sequence number to wait for before retrying.
     * If null, retry will be attempted periodically.
     */
    default boolean restartSession(int sessionID, String waitingSender, Integer waitingSequence) throws SQLException {
        return restartSession(sessionID);
    }

    boolean hasSessionCompleted(int sessionID) throws SQLException;

    boolean commitTransaction(int sessionID, Transaction tx) throws SQLException;

    void compensateTransactions(int sessionID) throws SQLException;

    List<RecoverableSession> recoverableSessions(int limit) throws SQLException;

    record RecoverableSession(Session session, String state, String waitingSender, Integer waitingSequence,
                              int restartCount, SpanContext traceContext) {
        public boolean isRestart() { return restartCount > 0; }
    }
}
