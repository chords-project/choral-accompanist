package choral.faulttolerance;

import choral.reactive.Session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class SqlDataStore implements FaultDataStore {
    private final Connection con;

    public SqlDataStore(Connection con) throws SQLException {
        this.con = con;
        createTables();
    }

    protected void createTables() throws SQLException {
        System.out.println("Creating tables in database...");
        try (Statement stmt = con.createStatement();) {
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

        try (PreparedStatement stmt = con.prepareStatement("INSERT INTO completed_sessions (session_id) VALUES (?) ON CONFLICT DO NOTHING;")) {
            stmt.setInt(1, session.sessionID());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean hasSessionCompleted(Session session) {
        System.out.println("Lookup session in database: " + session);

        try (PreparedStatement stmt = con.prepareStatement("SELECT * FROM completed_sessions WHERE session_id = ?;")) {
            stmt.setInt(1, session.sessionID());
            var result = stmt.executeQuery();

            // true if row was found
            return result.next();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        con.close();
    }
}
