package choral.faulttolerance;

import choral.reactive.Session;

import com.zaxxer.hikari.HikariDataSource;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;
import java.io.Closeable;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;


public class SQLDataStore implements FaultDataStore {
    public final DataSource db;

    public SQLDataStore(DataSource db) throws SQLException {
        this.db = db;
        createTables();
    }

    public static SQLDataStore createHikariDataStore(String url, String username, String password) throws SQLException {
        HikariDataSource db = new HikariDataSource();
        db.setJdbcUrl(url);
        db.setUsername(username);
        db.setPassword(password);

        return new SQLDataStore(db);
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

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("INSERT INTO session_states (session_id, session_state) VALUES (?, 'started');")
        ) {
            stmt.setInt(1, session.sessionID());
            int count = stmt.executeUpdate();
            if (count == 0) {
                System.out.println("- Failed to mark session as started in database: " + session);
            }
        }
    }

    @Override
    public void completeSession(Session session) throws SQLException {
        System.out.println("Marking session as completed in database: " + session);

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("UPDATE session_states SET session_state = 'completed' WHERE session_id = ? AND session_state = 'started';")
        ) {
            stmt.setInt(1, session.sessionID());
            int count = stmt.executeUpdate();
            if (count == 0) {
                System.out.println("- Failed to complete session in database: " + session);
            }
        }
    }

    @Override
    public void failSession(Session session) throws SQLException {
        System.out.println("Marking session as failed in database: " + session);

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("UPDATE session_states SET session_state = 'completed' WHERE session_id = ?;")
        ) {
            stmt.setInt(1, session.sessionID());
            stmt.executeUpdate();
        }
    }

    @Override
    public boolean hasSessionCompleted(Session session) throws SQLException {
        System.out.println("Lookup session in database: " + session);

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("SELECT * FROM session_states WHERE session_id = ? AND session_state IN ('completed', 'failed');")
        ) {
            stmt.setInt(1, session.sessionID());
            var result = stmt.executeQuery();

            // true if row was found
            return result.next();
        }
    }

    @Override
    public boolean commitTransaction(Session session, Transaction tx) throws SQLException {
        try (
                var con = db.getConnection();
        ) {
            con.setAutoCommit(false);

            // check that transaction has not already been commited
            try (var stmt = con.prepareStatement(
                    "SELECT * FROM transaction_states WHERE session_id = ? AND transaction_name = ?;"
            )) {
                stmt.setInt(1, session.sessionID());
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

            boolean success = tx.commit(session, new SQLTransaction(con));
            if (!success) {
                System.out.println("COMMIT FAILED, transaction returned false");
                con.rollback();
                return false;
            }

            // mark transaction as completed
            try (var stmt = con.prepareStatement("""
                    INSERT INTO transaction_states (session_id, transaction_name, transaction_state)
                    VALUES (?, ?, 'completed')
                    ON CONFLICT DO NOTHING;
                    """
            )) {
                stmt.setInt(1, session.sessionID());
                stmt.setString(2, tx.transactionName());
                stmt.execute();
            }

            con.commit();
        }

        // if we are here everything went well
        return true;
    }

    @Override
    public void compensateTransaction(Session session, Transaction tx) throws SQLException {
        try (
                var con = db.getConnection();
        ) {
            con.setAutoCommit(false);
            tx.compensate(session, new SQLTransaction(con));
            con.commit();
        }
    }

    @Override
    public void close() throws Exception {
        if (db instanceof Closeable) {
            ((Closeable) db).close();
        }
    }
}
