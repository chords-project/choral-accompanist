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
                    CREATE INDEX IF NOT EXISTS outbox_pending_destination_idx
                      ON outbox (destination, session_id, session_sender, sequence_num)
                      WHERE acknowledged = FALSE;
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
    public boolean aboutToSendMessage(Message message, String destination) throws SQLException, IOException {
        return prepareOutput(message, destination).acknowledged();
    }

    /**
     * Atomically creates an outbox message or returns the canonical bytes already stored for it.
     */
    public PreparedOutput prepareOutput(Message message, String destination) throws SQLException, IOException {

        try (
                var con = db.getConnection();
                PreparedStatement insertStmt = con.prepareStatement("""
                        INSERT INTO outbox (session_id, session_choreography, session_sender, message, sequence_num, destination, acknowledged)
                        VALUES (?, ?, ?, ?, ?, ?, FALSE) ON CONFLICT DO NOTHING;
                        """)
        ) {
            insertStmt.setInt(1, message.session.sessionID());
            insertStmt.setString(2, message.session.choreographyName());
            insertStmt.setString(3, message.session.senderName());
            insertStmt.setBytes(4, message.serialize());
            insertStmt.setInt(5, message.sequenceNumber);
            insertStmt.setString(6, destination);

            insertStmt.execute();
        }

        // Query the (potentially newly inserted) outbox message
        try (var con = db.getConnection(); var stmt = con.prepareStatement("""
                SELECT message, acknowledged FROM outbox
                WHERE session_id=? AND session_sender=? AND destination=? AND sequence_num=?
                """)) {
            bindKey(stmt, new OutboxKey(message.session.sessionID(), message.session.senderName(), destination, message.sequenceNumber));
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) throw new SQLException("outbox row disappeared after prepare");
                return new PreparedOutput(Message.deserialize(rs.getBytes(1)), destination, rs.getBoolean(2));
            } catch (ClassNotFoundException e) {
                throw new IOException("invalid stored outbox payload", e);
            }
        }
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

            // Ensure that ACKs arriving after choreography completes is ignored properly.
            try (var terminal = con.prepareStatement("SELECT session_state::text FROM session_states WHERE session_id=? FOR UPDATE")) {
                terminal.setInt(1, message.session.sessionID());
                try (var rs = terminal.executeQuery()) {
                    if (rs.next() && (rs.getString(1).equals("completed") || rs.getString(1).equals("failed"))) {
                        con.commit();
                        return;
                    }
                }
            }

            // The receive ACK must imply that both input and runnable work survive a crash.
            try (var sessionStmt = con.prepareStatement("""
                    INSERT INTO session_states (session_id, choreography, session_state, run_id, attempt_count,
                                                trace_id, trace_parent_span_id, trace_flags, trace_state)
                    VALUES (?, ?, 'started', CAST(? AS UUID), 0, ?, ?, ?, ?)
                    ON CONFLICT (session_id) DO NOTHING
                    """)) {
                sessionStmt.setInt(1, message.session.sessionID());
                sessionStmt.setString(2, message.session.choreographyName());
                sessionStmt.setString(3, message.session.benchmarkRunId());
                SQLDataStore.setTraceContext(sessionStmt, 4,
                        message.senderSpanContext == null ? null : message.senderSpanContext.toSpanContext());
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

    public List<Message> receivedMessages(int sessionId) throws SQLException, IOException {
        var messages = new ArrayList<Message>();
        try (var con = db.getConnection(); var stmt = con.prepareStatement(
                "SELECT message FROM inbox WHERE session_id=? ORDER BY session_sender, sequence_num")) {
            stmt.setInt(1, sessionId);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) try {
                    messages.add(Message.deserialize(rs.getBytes(1)));
                } catch (ClassNotFoundException e) {
                    throw new IOException("invalid inbox payload", e);
                }
            }
        }
        return messages;
    }

    public boolean hasReceived(int sessionId, String sender, int sequence) throws SQLException {
        try (var con = db.getConnection(); var stmt = con.prepareStatement(
                "SELECT 1 FROM inbox WHERE session_id=? AND session_sender=? AND sequence_num=?")) {
            stmt.setInt(1, sessionId);
            stmt.setString(2, sender);
            stmt.setInt(3, sequence);
            try (var rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<PreparedOutput> pendingOutputs(int limit) throws SQLException, IOException {
        var outputs = new ArrayList<PreparedOutput>();
        try (var con = db.getConnection(); var stmt = con.prepareStatement("""
                SELECT message, destination FROM (
                  SELECT message, destination,
                    ROW_NUMBER() OVER (PARTITION BY destination ORDER BY session_id, session_sender, sequence_num) AS destination_rank
                  FROM outbox WHERE acknowledged=FALSE
                ) pending ORDER BY destination_rank, destination LIMIT ?
                """)) {
            stmt.setInt(1, limit);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) try {
                    outputs.add(new PreparedOutput(Message.deserialize(rs.getBytes(1)), rs.getString(2), false));
                } catch (ClassNotFoundException e) {
                    throw new IOException("invalid outbox payload", e);
                }
            }
        }
        return outputs;
    }

    /**
     * Delete old messages, from the inbox and outbox database tables, that are no longer needed.
     *
     * @param limit the max number of messages to delete from each table.
     * @return the total number of deleted messages.
     */
    public int cleanupRegularMessages(int limit) throws SQLException {
        try (var con = db.getConnection()) {
            con.setAutoCommit(false);
            int deleted;
            try (var inbox = con.prepareStatement("""
                    DELETE FROM inbox WHERE ctid IN (SELECT i.ctid FROM inbox i JOIN session_states s
                    ON s.session_id=i.session_id WHERE s.session_state IN ('completed', 'failed') LIMIT ?)
                    """)) {
                inbox.setInt(1, limit);
                deleted = inbox.executeUpdate();
            }
            try (var outbox = con.prepareStatement("""
                    DELETE FROM outbox WHERE ctid IN (SELECT o.ctid FROM outbox o JOIN session_states s
                    ON s.session_id=o.session_id WHERE s.session_state IN ('completed', 'failed') AND o.acknowledged=TRUE LIMIT ?)
                    """)) {
                outbox.setInt(1, limit);
                deleted += outbox.executeUpdate();
            }
            con.commit();
            return deleted;
        }
    }

    /**
     * An outbox message ready for delivery
     *
     * @param message      the message object
     * @param destination  the intended receiver
     * @param acknowledged whether the message has been sent and acknowledged or not
     */
    public record PreparedOutput(Message message, String destination, boolean acknowledged) {
        public OutboxKey key() {
            return new OutboxKey(message.session.sessionID(), message.session.senderName(), destination, message.sequenceNumber);
        }
    }

    /**
     * The identity of a message in the outbox table
     *
     * @param sessionId   the session id of the choreography
     * @param sender      the name of the process that will send the message
     * @param destination the name of the process that will receive the message
     * @param sequence    the sequence number of the message
     */
    public record OutboxKey(int sessionId, String sender, String destination, int sequence) {
    }

    private static void bindKey(PreparedStatement stmt, OutboxKey key) throws SQLException {
        stmt.setInt(1, key.sessionId());
        stmt.setString(2, key.sender());
        stmt.setString(3, key.destination());
        stmt.setInt(4, key.sequence());
    }

    /**
     * Durable mailbox depths; acknowledged outbox rows are retained history, not backlog.
     */
    public long pendingOutboxCount() throws SQLException {
        return count("SELECT COUNT(*) FROM outbox WHERE acknowledged = FALSE");
    }

    public long totalOutboxCount() throws SQLException {
        return count("SELECT COUNT(*) FROM outbox");
    }

    public long inboxCount() throws SQLException {
        return count("SELECT COUNT(*) FROM inbox");
    }

    private long count(String query) throws SQLException {
        try (var con = db.getConnection(); var stmt = con.createStatement(); var rs = stmt.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
