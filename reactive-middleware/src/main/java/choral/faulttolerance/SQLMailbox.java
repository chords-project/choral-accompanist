package choral.faulttolerance;

import choral.reactive.Session;
import choral.reactive.connection.Message;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS outbox (
                      session_id INT NOT NULL,
                      session_choreography VARCHAR(255) NOT NULL,
                      session_sender VARCHAR(255) NOT NULL,
                      message BYTEA NOT NULL,
                      sequence_num INT NOT NULL,
                      acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
                      PRIMARY KEY (session_id, sequence_num)
                    );
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS inbox (
                      session_id INT NOT NULL,
                      session_choreography VARCHAR(255) NOT NULL,
                      session_sender VARCHAR(255) NOT NULL,
                      message BYTEA NOT NULL,
                      sequence_num INT NOT NULL,
                      PRIMARY KEY (session_id, sequence_num)
                    );
                    """);
        }
    }

    /**
     * Called right before sending a message from a client to server
     *
     * @param message the message to be sent
     * @return true if the message has already been sent and acknowledged, in that case the message need not be sent again
     */
    public boolean aboutToSendMessage(Message message) throws SQLException {

        try (
                var con = db.getConnection();
                PreparedStatement getStmt = con.prepareStatement("""
                        SELECT * FROM outbox WHERE session_id = ? AND session_choreography = ? AND session_sender = ? and sequence_num = ? and acknowledged = TRUE;
                        """);
                PreparedStatement insertStmt = con.prepareStatement("""
                        INSERT INTO outbox (session_id, session_choreography, session_sender, message, sequence_num, acknowledged)
                        VALUES (?, ?, ?, ?, ?, FALSE) ON CONFLICT DO NOTHING;
                        """)
        ) {
            getStmt.setInt(1, message.session.sessionID());
            getStmt.setString(2, message.session.choreographyName());
            getStmt.setString(3, message.session.senderName());
            getStmt.setInt(4, message.sequenceNumber);

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

            insertStmt.execute();
        }

        return false;
    }

    /**
     * Called after a sent message has been acknowledged by the sender.
     *
     * @param message the message that was successfully delivered.
     */
    public void didDeliverMessage(Message message) throws SQLException {
        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("""
                        UPDATE outbox SET acknowledged = TRUE WHERE session_id = ? AND session_choreography = ? AND sequence_num = ?;
                        """);
        ) {
            stmt.setInt(1, message.session.sessionID());
            stmt.setString(2, message.session.choreographyName());
            stmt.setInt(3, message.sequenceNumber);

            stmt.executeUpdate();
        }
    }

    public Optional<Message> willReceiveMessage(Session session, int sequenceNum) throws SQLException, IOException, ClassNotFoundException {
        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("""
                        SELECT * FROM inbox WHERE session_id = ? AND session_choreography = ? AND session_sender = ? and sequence_num = ?;
                        """);
        ) {
            stmt.setInt(1, session.sessionID());
            stmt.setString(2, session.choreographyName());
            stmt.setString(3, session.senderName());
            stmt.setInt(4, sequenceNum);

            var result = stmt.executeQuery();
            var messageFound = result.next();
            if (messageFound) {
                var messageBytes = result.getBytes("message");
                return Optional.of(Message.deserialize(messageBytes));
            }
        }

        return Optional.empty();
    }

    public void didReceiveMessage(Message message) throws SQLException {
        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("""
                        INSERT INTO inbox (session_id, session_choreography, session_sender, message, sequence_num)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING;
                        """);
        ) {
            stmt.setInt(1, message.session.sessionID());
            stmt.setString(2, message.session.choreographyName());
            stmt.setString(3, message.session.senderName());
            stmt.setBytes(4, message.serialize());
            stmt.setInt(5, message.sequenceNumber);

            stmt.execute();
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
                        WHERE session_states.session_state = 'started';
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
}















