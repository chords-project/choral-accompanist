package choral.faulttolerance;

import choral.reactive.Session;

import java.sql.SQLException;

public interface Transaction {
    /**
     * A unique name for this transaction within a choreography.
     */
    String transactionName();

    /**
     * Commits a transaction to the local database.
     * It returns whether the transaction was successful or not.
     * If not successful, the transaction will not be committed and the entire choreography will be rolled back.
     *
     * @param trans the transaction instance on which to execute the database modification.
     * @return true or false whether the transaction was successful or not.
     * @throws SQLException If an SQLException is thrown the transaction is marked as unsuccessful.
     */
    boolean commit(Session session, SQLTransaction trans) throws SQLException;

    /**
     * Compensated the previously commited transaction.
     * A compensation should be designed to never fail, and if it does it will be retried until successful.
     *
     * @param trans the transaction instance on which to execute the database modification.
     * @throws SQLException If an SQLException is thrown, the compensation failed and will be retried later.
     */
    void compensate(Session session, SQLTransaction trans) throws SQLException;
}
