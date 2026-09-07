package choral.accompanist.faulttolerance;

import choral.accompanist.connection.Message;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class SQLMailbox {
    public final DataSource db;

    public SQLMailbox(DataSource db) throws SQLException {
        this.db = db;
        this.createTables();
    }

    protected void createTables() throws SQLException {
        System.out.println("Creating mailbox tables in database...");

        try (
                var con = db.getConnection();
                Statement stmt = con.createStatement();
        ) {
            con.setAutoCommit(false);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS outbox (
                      session_id INT NOT NULL,
                      session_choreography VARCHAR(255) NOT NULL,
                      session_sender VARCHAR(255) NOT NULL,
                      message BYTEA NOT NULL,
                      sequence_num INT NOT NULL,
                      destination VARCHAR(255) NOT NULL,
                      acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
                      PRIMARY KEY (session_id, session_sender, destination, sequence_num)
                    );
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS inbox (
                      session_id INT NOT NULL,
                      session_choreography VARCHAR(255) NOT NULL,
                      session_sender VARCHAR(255) NOT NULL,
                      message BYTEA NOT NULL,
                      sequence_num INT NOT NULL,
                      PRIMARY KEY (session_id, session_sender, sequence_num)
                    );
                    """);
            con.commit();
        }
    }

    /**
     * Called right before sending a message from a client to server
     *
     * @param message the message to be sent
     * @return true if the message has already been sent and acknowledged, in that case the message need not be sent again
     */
    public boolean aboutToSendMessage(Message message, String destination) throws SQLException {

        try (
                var con = db.getConnection();
                PreparedStatement getStmt = con.prepareStatement("""
                        SELECT * FROM outbox WHERE session_id = ? AND session_choreography = ? AND session_sender = ? and sequence_num = ? and destination = ? and acknowledged = TRUE;
                        """);
                PreparedStatement insertStmt = con.prepareStatement("""
                        INSERT INTO outbox (session_id, session_choreography, session_sender, message, sequence_num, destination, acknowledged)
                        VALUES (?, ?, ?, ?, ?, ?, FALSE) ON CONFLICT DO NOTHING;
                        """)
        ) {
            getStmt.setInt(1, message.session.sessionID());
            getStmt.setString(2, message.session.choreographyName());
            getStmt.setString(3, message.session.senderName());
            getStmt.setInt(4, message.sequenceNumber);
            getStmt.setString(5, destination);

            var result = getStmt.executeQuery();

            // true if row was found
            boolean alreadyDelivered = result.next();
            if (alreadyDelivered) {
                return true;
            }

            insertStmt.setInt(1, message.session.sessionID());
            insertStmt.setString(2, message.session.choreographyName());
            insertStmt.setString(3, message.session.senderName());
            insertStmt.setBytes(4, message.serialize());
            insertStmt.setInt(5, message.sequenceNumber);
            insertStmt.setString(6, destination);

            insertStmt.execute();
        }

        return false;
    }

    /**
     * Called after a sent message has been acknowledged by the sender.
     *
     * @param message the message that was successfully delivered.
     */
    public void didDeliverMessage(Message message, String destination) throws SQLException {
        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("""
                        UPDATE outbox SET acknowledged = TRUE WHERE session_id = ? AND session_choreography = ? AND sequence_num = ? AND session_sender = ? AND destination = ?;
                        """);
        ) {
            stmt.setInt(1, message.session.sessionID());
            stmt.setString(2, message.session.choreographyName());
            stmt.setInt(3, message.sequenceNumber);
            stmt.setString(4, message.session.senderName());
            stmt.setString(5, destination);

            stmt.executeUpdate();
        }
    }

    public void didReceiveMessage(Message message) throws SQLException {
        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("""
                        INSERT INTO inbox (session_id, session_choreography, session_sender, message, sequence_num)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING;
                        """);
        ) {
            con.setAutoCommit(false);
            // The receive ACK must imply that both input and runnable work survive a crash.
            try (var sessionStmt = con.prepareStatement("""
                    INSERT INTO session_states (session_id, choreography, session_state, run_id, attempt_count)
                    VALUES (?, ?, 'started', CAST(? AS UUID), 0)
                    ON CONFLICT (session_id) DO NOTHING
                    """)) {
                sessionStmt.setInt(1, message.session.sessionID());
                sessionStmt.setString(2, message.session.choreographyName());
                sessionStmt.setString(3, message.session.benchmarkRunId());
                sessionStmt.executeUpdate();
            }
            stmt.setInt(1, message.session.sessionID());
            stmt.setString(2, message.session.choreographyName());
            stmt.setString(3, message.session.senderName());
            stmt.setBytes(4, message.serialize());
            stmt.setInt(5, message.sequenceNumber);

            stmt.execute();
            con.commit();
        }
    }

    public List<Message> recoverReceivedMessages() throws SQLException, IOException, ClassNotFoundException {
        try (
                var con = db.getConnection();
                var stmt = con.createStatement()
        ) {
            var resultSet = stmt.executeQuery("""
                    SELECT inbox.* FROM inbox
                        JOIN session_states ON inbox.session_id = session_states.session_id
                        WHERE session_states.session_state IN ('started', 'restart');
                    """);

            var messages = new ArrayList<Message>();
            while (resultSet.next()) {
                var msgBytes = resultSet.getBytes("message");
                messages.add(Message.deserialize(msgBytes));
            }

            System.out.println("Recovered " + messages.size() + " messages");

            return messages;
        }
    }

    /** Durable mailbox depths; acknowledged outbox rows are retained history, not backlog. */
    public long pendingOutboxCount() throws SQLException { return count("SELECT COUNT(*) FROM outbox WHERE acknowledged = FALSE"); }
    public long totalOutboxCount() throws SQLException { return count("SELECT COUNT(*) FROM outbox"); }
    public long inboxCount() throws SQLException { return count("SELECT COUNT(*) FROM inbox"); }
    private long count(String query) throws SQLException {
        try (var con = db.getConnection(); var stmt = con.createStatement(); var rs = stmt.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
