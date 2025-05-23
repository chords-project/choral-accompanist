package choral.faulttolerance;

import choral.reactive.Session;
import com.zaxxer.hikari.HikariDataSource;


import javax.sql.DataSource;
import java.io.Closeable;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class SQLDataStore implements FaultDataStore {
    private final DataSource db;

    public SQLDataStore(DataSource db) throws SQLException {
        this.db = db;
        createTables();
    }

    protected void createTables() throws SQLException {
        System.out.println("Creating tables in database...");

        try (
                var con = db.getConnection();
                Statement stmt = con.createStatement();
        ) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS completed_sessions (
                      session_id INT PRIMARY KEY
                    );
                    """);
        }
    }

    @Override
    public void completeSession(Session session) {
        System.out.println("Marking session as completed in database: " + session);

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("INSERT INTO completed_sessions (session_id) VALUES (?) ON CONFLICT DO NOTHING;")
        ) {
            stmt.setInt(1, session.sessionID());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean hasSessionCompleted(Session session) {
        System.out.println("Lookup session in database: " + session);

        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("SELECT * FROM completed_sessions WHERE session_id = ?;")
        ) {
            stmt.setInt(1, session.sessionID());
            var result = stmt.executeQuery();

            // true if row was found
            return result.next();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean commitTransaction(Session session, Transaction tx) throws SQLException {
        boolean success = false;
        
        try (
                var con = db.getConnection();
        ) {
            con.setAutoCommit(false);
            success = tx.commit(new SQLTransaction(con));
            con.commit();
        }

        return success;
    }

    @Override
    public void compensateTransaction(Session session, Transaction tx) throws SQLException {
        try (
                var con = db.getConnection();
        ) {
            con.setAutoCommit(false);
            tx.compensate(new SQLTransaction(con));
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
