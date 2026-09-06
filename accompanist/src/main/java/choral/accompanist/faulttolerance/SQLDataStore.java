package choral.accompanist.faulttolerance;

import choral.accompanist.Session;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.Closeable;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * A Postgres implementation of the {@link FaultDataStore} interface
 */
public class SQLDataStore implements FaultDataStore {
    public final DataSource db;
    public final Map<String, Transaction> transactions;
    private final Logger logger;

    public SQLDataStore(DataSource db, Set<Transaction> transactions) throws SQLException {
        logger = LoggerFactory.getLogger(SQLDataStore.class);

        this.transactions = new HashMap<>();
        for (Transaction tx : transactions) {
            this.transactions.put(tx.transactionName(), tx);
        }
        this.db = db;
        createTables();
    }

    public static SQLDataStore createHikariDataStore(String url, String username, String password, Set<Transaction> transactions) throws SQLException {
        HikariDataSource db = new HikariDataSource();
        db.setJdbcUrl(url);
        db.setUsername(username);
        db.setPassword(password);

        return new SQLDataStore(db, transactions);
    }

    protected void createTables() throws SQLException {
        logger.info("Creating tables in database...");

        try (
                var con = db.getConnection();
                Statement stmt = con.createStatement();
        ) {
            stmt.execute("""
                    -- create enum if not already exists
                    DO $$ BEGIN
                        IF to_regtype('session_state_enum') IS NULL THEN
                    		CREATE TYPE session_state_enum AS ENUM ('started', 'completed', 'failed', 'restart');
                        END IF;
                    END $$;
                    
                    CREATE TABLE IF NOT EXISTS session_states (
                      session_id INT PRIMARY KEY,
                      choreography VARCHAR(255) NOT NULL,
                      session_state session_state_enum NOT NULL,
                      run_id UUID,
                      started_at TIMESTAMPTZ,
                      completed_at TIMESTAMPTZ,
                      failed_at TIMESTAMPTZ,
                      attempt_count INTEGER NOT NULL DEFAULT 0,
                      restart_count INTEGER NOT NULL DEFAULT 0
                    );
                    """);

            stmt.execute("""
                    -- create enum if not already exists
                    DO $$ BEGIN
                        IF to_regtype('transaction_state_enum') IS NULL THEN
                    		CREATE TYPE transaction_state_enum AS ENUM ('completed', 'compensated');
                        END IF;
                    END $$;
                    
                    CREATE TABLE IF NOT EXISTS transaction_states (
                      session_id INT,
                      transaction_name VARCHAR(255),
                      transaction_state transaction_state_enum NOT NULL,
                      PRIMARY KEY (session_id, transaction_name)
                    );
                    """);
        }

        logger.info("Successfully created tables in database");
    }

    @Override
    public boolean startSession(Session session) throws SQLException {
        logger.info("Marking session as started in database: {}", session);

        try (var con = db.getConnection();
             var selectStmt = con.prepareStatement("SELECT * FROM session_states WHERE session_id = ?");
             PreparedStatement insertStmt = con.prepareStatement("""
                     INSERT INTO session_states (session_id, choreography, session_state, run_id, started_at, attempt_count)
                     VALUES (?, ?, 'started', CAST(? AS UUID), NOW(), 1)
                     ON CONFLICT (session_id) DO UPDATE SET session_state = 'started', started_at = NOW(),
                         attempt_count = session_states.attempt_count + 1;
                     """)) {
            con.setAutoCommit(false);

            boolean insertSession = true;

            // Check if session already exists in database
            selectStmt.setInt(1, session.sessionID());

            var rs = selectStmt.executeQuery();
            if (rs.next()) {
                var choreography = rs.getString("choreography");
                if (!Objects.equals(session.choreographyName(), choreography)) {
                    throw new SQLException("choreography in session_states table did not match start session");
                }

                var state = rs.getString("session_state");

                switch (state) {
                    case "started":
                        insertSession = false;
                        break;
                    case "restart":
                        break;
                    case "completed":
                    case "failed":
                        throw new SQLException("attempt to start session that has already been processed");
                }
            }

            if (insertSession) {
                insertStmt.setInt(1, session.sessionID());
                insertStmt.setString(2, session.choreographyName());
                insertStmt.setString(3, session.benchmarkRunId());
                int count = insertStmt.executeUpdate();
                if (count == 0) {
                    throw new SQLException("failed to mark session as started in database: " + session.sessionID());
                }
            }

            con.commit();
            return insertSession;
        }
    }

    @Override
    public boolean completeSession(int sessionID) throws SQLException {
        logger.info("Marking session as completed in database: {}", sessionID);

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("UPDATE session_states SET session_state = 'completed', completed_at = NOW() WHERE session_id = ? AND session_state = 'started';")
        ) {
            stmt.setInt(1, sessionID);
            int count = stmt.executeUpdate();
            if (count == 0) {
                logger.warn("- Failed to complete session in database: " + sessionID);
            }
            return count == 1;
        }
    }

    @Override
    public boolean failSession(Session session) throws SQLException {
        logger.warn("Marking session as failed in database: " + session.sessionID());

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("""
                        INSERT INTO session_states (session_id, choreography, session_state, run_id, failed_at)
                        VALUES (?, ?, 'failed', CAST(? AS UUID), NOW())
                        ON CONFLICT (session_id) DO UPDATE SET session_state = 'failed', failed_at = NOW()
                        WHERE session_states.session_state <> 'failed';
                        """)
        ) {
            stmt.setInt(1, session.sessionID());
            stmt.setString(2, session.choreographyName());
            stmt.setString(3, session.benchmarkRunId());
            return stmt.executeUpdate() == 1;
        }
    }

    @Override
    public boolean restartSession(int sessionID) throws SQLException {
        logger.info("Marking session to be restarted in database: {}", sessionID);

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("UPDATE session_states SET session_state = 'restart', restart_count = restart_count + 1 WHERE session_id = ? AND session_state IN ('started', 'completed');")
        ) {
            stmt.setInt(1, sessionID);
            int count = stmt.executeUpdate();
            if (count == 0) {
                logger.warn("- Failed to mark session to restart in database: {}", sessionID);
            }
            return count == 1;
        }
    }

    @Override
    public boolean hasSessionCompleted(int sessionID) throws SQLException {
        logger.info("Lookup session in database: {}", sessionID);

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("SELECT * FROM session_states WHERE session_id = ? AND session_state IN ('completed', 'failed');")
        ) {
            stmt.setInt(1, sessionID);
            var result = stmt.executeQuery();

            // true if row was found
            return result.next();
        }
    }

    @Override
    public boolean commitTransaction(int sessionID, Transaction tx) throws SQLException {
        try (
                var con = db.getConnection();
        ) {
            con.setAutoCommit(false);

            // check that transaction has not already been commited
            try (var stmt = con.prepareStatement(
                    "SELECT * FROM transaction_states WHERE session_id = ? AND transaction_name = ?;"
            )) {
                stmt.setInt(1, sessionID);
                stmt.setString(2, tx.transactionName());

                try (var resultSet = stmt.executeQuery()) {
                    var foundRow = resultSet.next();
                    if (foundRow) {
                        String state = resultSet.getString("transaction_state");
                        logger.info("COMMIT IGNORED, transaction already committed: state={}", state);
                        con.rollback();
                        return true; // duplicate commit is not a failure
                    }
                }
            }

            boolean success = tx.commit(sessionID, new SQLTransaction(con));
            if (!success) {
                logger.warn("COMMIT FAILED, transaction returned false");
                con.rollback();
                return false;
            } else {
                // mark transaction as completed
                try (var stmt = con.prepareStatement("""
                        
                            INSERT INTO transaction_states (session_id, transaction_name, transaction_state)
                        VALUES (?, ?, 'completed')
                        ON CONFLICT DO NOTHING;
                        
                        """
                )) {
                    stmt.setInt(1, sessionID);
                    stmt.setString(
                            2, tx.

                                    transactionName());
                    stmt.execute();
                }

                con.commit();

                // if we are here everything went well
                return true;
            }
        }
    }

    @Override
    public void compensateTransactions(int sessionID) throws SQLException {
        logger.info("Compensating transactions for session: {}", sessionID);

        try (
                var con = db.getConnection();
                var stmt = con.prepareStatement("SELECT * FROM transaction_states WHERE transaction_state = 'completed' AND session_id = ?;");
        ) {
            stmt.setInt(1, sessionID);
            try (
                    var transResult = stmt.executeQuery();
                    var compensateCon = db.getConnection();
            ) {
                compensateCon.setAutoCommit(false);

                while (transResult.next()) {
                    var txName = transResult.getString("transaction_name");
                    var tx = transactions.get(txName);
                    logger.info("- Compensating transaction: {}", txName);

                    tx.compensate(sessionID, new SQLTransaction(compensateCon));

                    try (var updateTransStmt = compensateCon.prepareStatement("UPDATE transaction_states SET transaction_state = 'compensated' WHERE session_id = ?;")) {
                        updateTransStmt.setInt(1, sessionID);
                        updateTransStmt.executeUpdate();
                    }

                    compensateCon.commit();
                }
            }
        }
    }

    @Override
    public List<Session> recoverStartedSessions() throws SQLException {
        var result = new ArrayList<Session>();
        try (
                var con = db.getConnection();
                var stmt = con.createStatement();
        ) {
            var rs = stmt.executeQuery("SELECT * FROM session_states WHERE session_state IN ('started', 'restart');");
            while (rs.next()) {
                var sessionID = rs.getInt("session_id");
                var choreography = rs.getString("choreography");
                result.add(new Session(choreography, "", sessionID, rs.getString("run_id")));
            }
        }

        logger.info("Found {} pending sessions to restart", result.size());

        return result;
    }

    @Override
    public void close() throws Exception {
        if (db instanceof Closeable) {
            ((Closeable) db).close();
        }
    }
}
