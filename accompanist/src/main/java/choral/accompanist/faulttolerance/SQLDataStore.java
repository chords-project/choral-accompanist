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
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

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
                      restart_count INTEGER NOT NULL DEFAULT 0,
                      waiting_sender VARCHAR(255),
                      waiting_sequence INTEGER,
                      trace_id VARCHAR(32),
                      trace_parent_span_id VARCHAR(16),
                      trace_flags SMALLINT,
                      trace_state TEXT,
                      CONSTRAINT session_waiting_pair CHECK
                        ((waiting_sender IS NULL) = (waiting_sequence IS NULL))
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

            stmt.execute("""
                    ALTER TABLE session_states ADD COLUMN IF NOT EXISTS trace_id VARCHAR(32);
                    ALTER TABLE session_states ADD COLUMN IF NOT EXISTS trace_parent_span_id VARCHAR(16);
                    ALTER TABLE session_states ADD COLUMN IF NOT EXISTS trace_flags SMALLINT;
                    ALTER TABLE session_states ADD COLUMN IF NOT EXISTS trace_state TEXT;
                    """);
        }

        logger.info("Successfully created tables in database");
    }

    @Override
    public boolean startSession(Session session) throws SQLException {
        return startSession(session, SpanContext.getInvalid());
    }

    @Override
    public boolean startSession(Session session, SpanContext traceContext) throws SQLException {
        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("""
                        INSERT INTO session_states (session_id, choreography, session_state, run_id, started_at, attempt_count,
                                                    trace_id, trace_parent_span_id, trace_flags, trace_state)
                        VALUES (?, ?, 'started', CAST(? AS UUID), NOW(), 1, ?, ?, ?, ?)
                        ON CONFLICT (session_id) DO UPDATE SET session_state = 'started', started_at = COALESCE(session_states.started_at, EXCLUDED.started_at),
                            waiting_sender = NULL, waiting_sequence = NULL,
                            trace_id = COALESCE(session_states.trace_id, EXCLUDED.trace_id),
                            trace_parent_span_id = COALESCE(session_states.trace_parent_span_id, EXCLUDED.trace_parent_span_id),
                            trace_flags = COALESCE(session_states.trace_flags, EXCLUDED.trace_flags),
                            trace_state = COALESCE(session_states.trace_state, EXCLUDED.trace_state),
                            attempt_count = session_states.attempt_count + 1
                        WHERE session_states.choreography = EXCLUDED.choreography
                          AND (session_states.session_state = 'restart'
                            OR (session_states.session_state = 'started' AND session_states.attempt_count = 0))
                        RETURNING session_id;
                        """)
        ) {
            stmt.setInt(1, session.sessionID());
            stmt.setString(2, session.choreographyName());
            stmt.setString(3, session.benchmarkRunId());
            setTraceContext(stmt, 4, traceContext);

            try (var result = stmt.executeQuery()) {
                return result.next();
            }
        }
    }

    @Override
    public boolean completeSession(int sessionID) throws SQLException {
        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("UPDATE session_states SET session_state = 'completed', completed_at = NOW(), waiting_sender = NULL, waiting_sequence = NULL WHERE session_id = ? AND session_state = 'started';")
        ) {
            stmt.setInt(1, sessionID);
            int count = stmt.executeUpdate();
            return count == 1;
        }
    }

    @Override
    public boolean failSession(Session session) throws SQLException {
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
        return restartSession(sessionID, null, null);
    }

    @Override
    public boolean restartSession(int sessionID, String waitingSender, Integer waitingSequence) throws SQLException {
        try (
                var con = db.getConnection();
                PreparedStatement stmt = con.prepareStatement("UPDATE session_states SET session_state = 'restart', waiting_sender = ?, waiting_sequence = ?, restart_count = restart_count + 1 WHERE session_id = ? AND session_state = 'started';")
        ) {
            stmt.setString(1, waitingSender);
            if (waitingSequence == null) stmt.setNull(2, java.sql.Types.INTEGER); else stmt.setInt(2, waitingSequence);
            stmt.setInt(3, sessionID);
            int count = stmt.executeUpdate();
            return count == 1;
        }
    }

    @Override
    public boolean hasSessionCompleted(int sessionID) throws SQLException {
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
        try (var con = db.getConnection()) {
            con.setAutoCommit(false);

            // Claim the transaction before executing it. The unique key serializes
            // concurrent callers, and a rollback also rolls back this claim.
            try (var stmt = con.prepareStatement("""
                    INSERT INTO transaction_states (session_id, transaction_name, transaction_state)
                    VALUES (?, ?, 'completed')
                    ON CONFLICT DO NOTHING
                    RETURNING transaction_name;
                    """)) {
                stmt.setInt(1, sessionID);
                stmt.setString(2, tx.transactionName());

                try (var resultSet = stmt.executeQuery()) {
                    if (!resultSet.next()) {
                        con.rollback();
                        return true; // duplicate commit is not a failure
                    }
                }
            }

            boolean success = tx.commit(sessionID, new SQLTransaction(con));
            if (!success) {
                con.rollback();
                return false;
            }

            con.commit();
            return true;
        }
    }

    @Override
    public void compensateTransactions(int sessionID) throws SQLException {
        try (var con = db.getConnection()) {
            con.setAutoCommit(false);

            while (true) {
                String txName;
                try (var stmt = con.prepareStatement("""
                        SELECT transaction_name
                        FROM transaction_states
                        WHERE session_id = ? AND transaction_state = 'completed'
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED;
                        """)) {
                    stmt.setInt(1, sessionID);
                    try (var result = stmt.executeQuery()) {
                        if (!result.next()) {
                            con.rollback();
                            return;
                        }
                        txName = result.getString("transaction_name");
                    }
                }

                var tx = transactions.get(txName);
                if (tx == null) {
                    con.rollback();
                    throw new SQLException("unknown transaction in transaction_states: " + txName);
                }

                tx.compensate(sessionID, new SQLTransaction(con));

                try (var stmt = con.prepareStatement("""
                        UPDATE transaction_states
                        SET transaction_state = 'compensated'
                        WHERE session_id = ? AND transaction_name = ? AND transaction_state = 'completed';
                        """)) {
                    stmt.setInt(1, sessionID);
                    stmt.setString(2, txName);
                    if (stmt.executeUpdate() != 1) {
                        con.rollback();
                        throw new SQLException("failed to mark transaction as compensated: " + txName);
                    }
                }

                con.commit();
            }
        }
    }

    @Override
    public List<RecoverableSession> recoverableSessions(int limit) throws SQLException {
        var sessions = new ArrayList<RecoverableSession>();
        try (var con = db.getConnection(); var stmt = con.prepareStatement("""
                SELECT session_id, choreography, run_id, session_state::text, waiting_sender, waiting_sequence,
                       restart_count, trace_id, trace_parent_span_id, trace_flags, trace_state
                FROM session_states WHERE session_state IN ('started','restart') ORDER BY session_id LIMIT ?
                """)) {
            stmt.setInt(1, limit);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Integer seq = (Integer) rs.getObject("waiting_sequence");
                    sessions.add(new RecoverableSession(
                            new Session(rs.getString("choreography"), "", rs.getInt("session_id"), rs.getString("run_id")),
                            rs.getString("session_state"), rs.getString("waiting_sender"), seq,
                            rs.getInt("restart_count"), readTraceContext(rs)));
                }
            }
        }
        return sessions;
    }

    static void setTraceContext(PreparedStatement stmt, int firstIndex, SpanContext context) throws SQLException {
        if (context == null || !context.isValid()) {
            stmt.setNull(firstIndex, java.sql.Types.VARCHAR);
            stmt.setNull(firstIndex + 1, java.sql.Types.VARCHAR);
            stmt.setNull(firstIndex + 2, java.sql.Types.SMALLINT);
            stmt.setNull(firstIndex + 3, java.sql.Types.VARCHAR);
            return;
        }
        stmt.setString(firstIndex, context.getTraceId());
        stmt.setString(firstIndex + 1, context.getSpanId());
        stmt.setInt(firstIndex + 2, Byte.toUnsignedInt(context.getTraceFlags().asByte()));
        stmt.setString(firstIndex + 3, encodeTraceState(context.getTraceState()));
    }

    private static SpanContext readTraceContext(java.sql.ResultSet rs) throws SQLException {
        String traceId = rs.getString("trace_id");
        String spanId = rs.getString("trace_parent_span_id");
        if (traceId == null || spanId == null) return SpanContext.getInvalid();
        var state = TraceState.builder();
        String encodedState = rs.getString("trace_state");
        if (encodedState != null && !encodedState.isBlank())
            for (String entry : encodedState.split(",")) {
                int separator = entry.indexOf('=');
                if (separator > 0) state.put(entry.substring(0, separator), entry.substring(separator + 1));
            }
        return SpanContext.createFromRemoteParent(traceId, spanId,
                TraceFlags.fromByte((byte) rs.getInt("trace_flags")), state.build());
    }

    static String encodeTraceState(TraceState state) {
        return String.join(",", state.asMap().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toList());
    }

    @Override
    public void close() throws Exception {
        if (db instanceof Closeable) {
            ((Closeable) db).close();
        }
    }
}
