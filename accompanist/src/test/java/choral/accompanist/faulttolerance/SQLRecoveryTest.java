package choral.accompanist.faulttolerance;

import choral.accompanist.Session;
import choral.accompanist.connection.Message;
import com.zaxxer.hikari.HikariDataSource;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Uses a fresh schema per test; never modifies existing application tables. */
@EnabledIfEnvironmentVariable(named = "ACCOMPANIST_TEST_POSTGRES_URL", matches = ".+")
class SQLRecoveryTest {
    HikariDataSource db;
    SQLDataStore store;
    SQLMailbox mailbox;
    String schema;

    @BeforeEach void setup() throws Exception {
        db = new HikariDataSource();
        db.setJdbcUrl(System.getenv("ACCOMPANIST_TEST_POSTGRES_URL"));
        schema = "recovery_" + UUID.randomUUID().toString().replace("-", "");
        try (var con = java.sql.DriverManager.getConnection(System.getenv("ACCOMPANIST_TEST_POSTGRES_URL"));
             var stmt = con.createStatement()) {
            stmt.execute("CREATE SCHEMA " + schema);
        }
        db.setSchema(schema);
        store = new SQLDataStore(db, Set.of());
        mailbox = new SQLMailbox(db);
    }

    @AfterEach void teardown() throws Exception {
        if (db != null) {
            try { sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
            finally { db.close(); }
        }
    }

    void sql(String query) throws SQLException {
        try (var con = db.getConnection(); var stmt = con.createStatement()) { stmt.execute(query); }
    }

    long count(String query) throws SQLException {
        try (var con = db.getConnection(); var stmt = con.createStatement(); var rs = stmt.executeQuery(query)) {
            rs.next(); return rs.getLong(1);
        }
    }

    Message message(int id, String sender) {
        return new Message(new Session("test", sender, id), sender + " payload", 1);
    }

    Transaction transaction(String name, AtomicInteger commits, AtomicInteger compensations,
                            boolean slow, boolean failCompensation) {
        return new Transaction() {
            @Override public String transactionName() { return name; }

            @Override public boolean commit(int sessionID, SQLTransaction trans) throws SQLException {
                commits.incrementAndGet();
                if (slow) {
                    try (var stmt = trans.prepareStatement("SELECT pg_sleep(0.2)")) {
                        stmt.execute();
                    }
                }
                try (var stmt = trans.prepareStatement("INSERT INTO transaction_effects VALUES (?, ?)")) {
                    stmt.setInt(1, sessionID);
                    stmt.setString(2, name);
                    stmt.executeUpdate();
                }
                return true;
            }

            @Override public void compensate(int sessionID, SQLTransaction trans) throws SQLException {
                compensations.incrementAndGet();
                if (slow) {
                    try (var stmt = trans.prepareStatement("SELECT pg_sleep(0.2)")) {
                        stmt.execute();
                    }
                }
                if (failCompensation) throw new SQLException("test compensation failure");
                try (var stmt = trans.prepareStatement("DELETE FROM transaction_effects WHERE session_id = ? AND tx_name = ?")) {
                    stmt.setInt(1, sessionID);
                    stmt.setString(2, name);
                    stmt.executeUpdate();
                }
            }
        };
    }

    @Test void identitiesSeparateSendersAndDestinations() throws Exception {
        var a = message(1, "a");
        var b = message(1, "b");
        mailbox.didReceiveMessage(a);
        mailbox.didReceiveMessage(b);
        mailbox.didReceiveMessage(a);
        assertEquals(2, mailbox.inboxCount());
        assertFalse(mailbox.aboutToSendMessage(a, "one"));
        assertFalse(mailbox.aboutToSendMessage(a, "two"));
        assertFalse(mailbox.aboutToSendMessage(b, "one"));
        mailbox.didDeliverMessage(a, "one");
        assertTrue(mailbox.aboutToSendMessage(a, "one"));
        assertFalse(mailbox.aboutToSendMessage(a, "two"));
        assertFalse(mailbox.aboutToSendMessage(b, "one"));
        assertEquals(3, mailbox.totalOutboxCount());
        assertEquals(2, mailbox.pendingOutboxCount());
    }

    @Test void outputPreparationReturnsCanonicalPersistedPayload() throws Exception {
        var first = message(10, "sender");
        var changed = new Message(first.session, "different replay payload", 1);
        assertEquals(first.message, mailbox.prepareOutput(first, "peer").message().message);
        assertEquals(first.message, mailbox.prepareOutput(changed, "peer").message().message);
        mailbox.didDeliverMessage(first, "peer");
        assertTrue(mailbox.prepareOutput(changed, "peer").acknowledged());
    }

    @Test void receiveDependencyAndCleanupRulesAreDurable() throws Exception {
        var input = message(11, "sender");
        mailbox.didReceiveMessage(input);
        assertTrue(store.startSession(input.session));
        assertTrue(store.restartSession(11, "sender", 2));
        var waiting = store.recoverableSessions(10).getFirst();
        assertEquals("sender", waiting.waitingSender());
        assertEquals(2, waiting.waitingSequence());
        assertFalse(mailbox.hasReceived(11, "sender", 2));
        assertTrue(store.startSession(input.session));
        assertNull(store.recoverableSessions(10).getFirst().waitingSender());
        assertFalse(mailbox.aboutToSendMessage(input, "peer"));
        assertTrue(store.completeSession(11));
        mailbox.cleanupRegularMessages(100);
        assertEquals(0, mailbox.inboxCount());
        assertEquals(1, mailbox.pendingOutboxCount(), "completed sessions retain pending output");
        mailbox.didDeliverMessage(input, "peer");
        mailbox.cleanupRegularMessages(100);
        assertEquals(0, mailbox.totalOutboxCount());
        assertEquals(1, count("SELECT COUNT(*) FROM session_states WHERE session_id=11 AND session_state='completed'"));
    }

    @Test void receiptSurvivesCrashBeforeExecutionStarts() throws Exception {
        var input = message(2, "sender");
        var parent = SpanContext.createFromRemoteParent(
                "0123456789abcdef0123456789abcdef", "0123456789abcdef",
                TraceFlags.getSampled(), TraceState.builder().put("vendor", "value").build());
        input.senderSpanContext = new Message.SerializedSpanContext(parent);
        mailbox.didReceiveMessage(input);
        // Construct fresh components without ever calling startSession on the originals.
        var recoveredStore = new SQLDataStore(db, Set.of());
        var recoveredMailbox = new SQLMailbox(db);
        var candidate = recoveredStore.recoverableSessions(10).getFirst();
        assertEquals(2, candidate.session().sessionID());
        assertFalse(candidate.isRestart());
        assertEquals(parent.getTraceId(), candidate.traceContext().getTraceId());
        assertEquals(parent.getSpanId(), candidate.traceContext().getSpanId());
        assertEquals("value", candidate.traceContext().getTraceState().get("vendor"));
        assertEquals(input.message, recoveredMailbox.recoverReceivedMessages().getFirst().message);
        assertTrue(recoveredStore.startSession(input.session));
        assertEquals(1, count("SELECT attempt_count FROM session_states WHERE session_id = 2"));
    }

    @Test void recoveryClassificationRequiresARecordedRestart() throws Exception {
        var session = message(12, "sender").session;
        var parent = SpanContext.createFromRemoteParent(
                "fedcba9876543210fedcba9876543210", "fedcba9876543210",
                TraceFlags.getDefault(), TraceState.getDefault());
        assertTrue(store.startSession(session, parent));
        assertFalse(store.recoverableSessions(10).getFirst().isRestart());
        assertTrue(store.restartSession(session.sessionID()));
        var restarted = store.recoverableSessions(10).getFirst();
        assertTrue(restarted.isRestart());
        assertEquals(parent.getTraceId(), restarted.traceContext().getTraceId());
        assertEquals(parent.getSpanId(), restarted.traceContext().getSpanId());
    }

    @Test void concurrentStartsAreAcquiredOnlyOnce() throws Exception {
        var session = message(6, "sender").session;
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return store.startSession(session);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return store.startSession(session);
            });

            ready.await();
            start.countDown();
            assertNotEquals(first.get(), second.get());
        }

        assertEquals(1, count("SELECT attempt_count FROM session_states WHERE session_id = 6"));
    }

    @Test void concurrentTransactionCommitsExecuteOnlyOnce() throws Exception {
        sql("CREATE TABLE transaction_effects (session_id INT, tx_name VARCHAR(255))");
        var commits = new AtomicInteger();
        var tx = transaction("charge", commits, new AtomicInteger(), true, false);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return store.commitTransaction(7, tx);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return store.commitTransaction(7, tx);
            });
            ready.await();
            start.countDown();
            assertTrue(first.get());
            assertTrue(second.get());
        }

        assertEquals(1, commits.get());
        assertEquals(1, count("SELECT COUNT(*) FROM transaction_effects WHERE session_id = 7"));
        assertEquals(1, count("SELECT COUNT(*) FROM transaction_states WHERE session_id = 7 AND transaction_state = 'completed'"));
    }

    @Test void concurrentCompensationExecutesOnlyOnce() throws Exception {
        sql("CREATE TABLE transaction_effects (session_id INT, tx_name VARCHAR(255))");
        var compensations = new AtomicInteger();
        var tx = transaction("charge", new AtomicInteger(), compensations, true, false);
        var compensationStore = new SQLDataStore(db, Set.of(tx));
        assertTrue(compensationStore.commitTransaction(8, tx));

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                compensationStore.compensateTransactions(8);
                return null;
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                compensationStore.compensateTransactions(8);
                return null;
            });
            ready.await();
            start.countDown();
            first.get();
            second.get();
        }

        assertEquals(1, compensations.get());
        assertEquals(0, count("SELECT COUNT(*) FROM transaction_effects WHERE session_id = 8"));
        assertEquals(1, count("SELECT COUNT(*) FROM transaction_states WHERE session_id = 8 AND transaction_state = 'compensated'"));
    }

    @Test void failedCompensationDoesNotMarkOtherTransactionsCompensated() throws Exception {
        sql("CREATE TABLE transaction_effects (session_id INT, tx_name VARCHAR(255))");
        var successful = transaction("a-success", new AtomicInteger(), new AtomicInteger(), false, false);
        var failing = transaction("b-failing", new AtomicInteger(), new AtomicInteger(), false, true);
        var compensationStore = new SQLDataStore(db, Set.of(successful, failing));
        assertTrue(compensationStore.commitTransaction(9, successful));
        assertTrue(compensationStore.commitTransaction(9, failing));

        assertThrows(SQLException.class, () -> compensationStore.compensateTransactions(9));

        assertEquals(1, count("SELECT COUNT(*) FROM transaction_states WHERE session_id = 9 AND transaction_name = 'a-success' AND transaction_state = 'compensated'"));
        assertEquals(1, count("SELECT COUNT(*) FROM transaction_states WHERE session_id = 9 AND transaction_name = 'b-failing' AND transaction_state = 'completed'"));
    }

    @Test void failedInboxWriteRollsBackSessionRegistration() throws Exception {
        sql("ALTER TABLE inbox ADD CONSTRAINT reject_test_message CHECK (sequence_num <> 1)");
        assertThrows(SQLException.class, () -> mailbox.didReceiveMessage(message(3, "sender")));
        assertEquals(0, count("SELECT COUNT(*) FROM session_states"));
        assertEquals(0, mailbox.inboxCount());
    }

    @Test void completedAndFailedSessionsCannotRestart() throws Exception {
        var completed = message(4, "sender").session;
        assertTrue(store.startSession(completed));
        assertTrue(store.completeSession(4));
        assertFalse(store.restartSession(4));
        assertFalse(store.completeSession(4));
        assertEquals(1, count("SELECT COUNT(*) FROM session_states WHERE session_id = 4 AND session_state = 'completed' AND restart_count = 0"));
        var failed = message(5, "sender").session;
        store.failSession(failed);
        assertFalse(store.restartSession(5));
    }
}
