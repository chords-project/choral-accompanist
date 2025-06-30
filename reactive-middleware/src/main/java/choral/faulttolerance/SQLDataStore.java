package choral.faulttolerance;

import choral.reactive.Session;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.Closeable;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;


public class SQLDataStore implements FaultDataStore {
    public final DataSource db;
    public final Map<String, Transaction> transactions;

    public SQLDataStore(DataSource db, Set<Transaction> transactions) throws SQLException {
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
        System.out.println("Creating tables in database...");

        try (
                var con = db.getConnection();
                Statement stmt = con.createStatement();
        ) {
            stmt.execute("""
                    -- create enum if not already exists
                    DO $$ BEGIN
                        IF to_regtype('session_state_enum') IS NULL THEN
                    		CREATE TYPE session_state_enum AS ENUM ('started', 'completed', 'failed');
                        END IF;
                    END $$;
                    
                    CREATE TABLE IF NOT EXISTS session_states (
                      session_id INT PRIMARY KEY,
                      choreography VARCHAR(255) NOT NULL,
                      session_state session_state_enum NOT NULL
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
    }

    @Override
    public void startSession(Session session) throws SQLException {
        System.out.println("Marking session as started in database: " + session);

        // TODO: Check that the session has not already been completed or failed.

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("INSERT INTO session_states (session_id, choreography, session_state) VALUES (?, ?, 'started');")
        ) {
            stmt.setInt(1, session.sessionID());
            stmt.setString(2, session.choreographyName());
            int count = stmt.executeUpdate();
            if (count == 0) {
                System.out.println("- Failed to mark session as started in database: " + session.sessionID());
            }
        }
    }

    @Override
    public void completeSession(int sessionID) throws SQLException {
        System.out.println("Marking session as completed in database: " + sessionID);

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("UPDATE session_states SET session_state = 'completed' WHERE session_id = ? AND session_state = 'started';")
        ) {
            stmt.setInt(1, sessionID);
            int count = stmt.executeUpdate();
            if (count == 0) {
                System.out.println("- Failed to complete session in database: " + sessionID);
            }
        }
    }

    @Override
    public void failSession(int sessionID) throws SQLException {
        System.out.println("Marking session as failed in database: " + sessionID);

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("UPDATE session_states SET session_state = 'failed' WHERE session_id = ?;")
        ) {
            stmt.setInt(1, sessionID);
            stmt.executeUpdate();
        }
    }

    @Override
    public boolean hasSessionCompleted(int sessionID) throws SQLException {
        System.out.println("Lookup session in database: " + sessionID);

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
                        System.out.println("COMMIT IGNORED, transaction already committed: state=" + state);
                        con.rollback();
                        return true; // duplicate commit is not a failure
                    }
                }
            }

            boolean success = tx.commit(sessionID, new SQLTransaction(con));
            if (!success) {
                System.out.println("COMMIT FAILED, transaction returned false");
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
        System.out.println("Compensating transactions for session: " + sessionID);

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
                    System.out.println("- Compensating transaction: " + txName);

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
            var rs = stmt.executeQuery("SELECT * FROM session_states WHERE session_state = 'started';");
            while (rs.next()) {
                var sessionID = rs.getInt("session_id");
                var choreography = rs.getString("choreography");
                result.add(new Session(choreography, "", sessionID));
            }
        }

        System.out.println("Found " + result.size() + " pending sessions to restart");

        return result;
    }

    @Override
    public void close() throws Exception {
        if (db instanceof Closeable) {
            ((Closeable) db).close();
        }
    }
}
