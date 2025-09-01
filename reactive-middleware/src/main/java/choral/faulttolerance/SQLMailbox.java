package choral.faulttolerance;

import choral.reactive.connection.Message;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
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
                PreparedStatement stmt = con.prepareStatement("""
                        SELECT * FROM outbox WHERE session_id = ? AND session_choreography = ? AND session_sender = ? and sequence_num = ? and acknowledged = TRUE;
                        """)
        ) {
            stmt.setInt(1, message.session.sessionID());
            stmt.setString(2, message.session.choreographyName());
            stmt.setString(3, message.session.senderName());
            stmt.setInt(4, message.sequenceNumber);

            var result = stmt.executeQuery();

            // true if row was found
            return result.next();
        }
    }

    public List<Message> recoverReceivedMessages() throws SQLException {
        return List.of();
    }
}















