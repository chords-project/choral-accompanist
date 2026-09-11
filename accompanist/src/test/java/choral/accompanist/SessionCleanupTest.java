package choral.accompanist;

import choral.accompanist.tracing.TelemetrySession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionCleanupTest {
    @Test void exceptionReleasesRegistrationAndEverySenderQueue() throws Exception {
        var session = new Session("test", "local", 91);
        class Server extends ReactiveServer {
            Server() {
                super("local", ctx -> { throw new IllegalStateException("test failure"); });
            }
            boolean registered() { return knownSessionIDs.contains(91) || telemetrySessionMap.containsKey(91); }
            @Override protected void sessionExecutionFailed(TelemetrySession telemetry, Exception error) {
                assertTrue(registered(), "Recovery must finish before another attempt can register");
                throw new IllegalArgumentException("recovery hook failure");
            }
        }
        var server = new Server();
        var telemetry = TelemetrySession.makeNoop(session);
        var a = session.replacingSender("a");
        var b = session.replacingSender("b");
        var waiting = server.msgQueue.retrieveMessage(a, telemetry);
        server.msgQueue.addMessage(b, "old", 1, telemetry);
        var failure = assertThrows(IllegalStateException.class, () -> server.invokeManualSession(telemetry));
        assertEquals("test failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertFalse(server.registered());
        assertTrue(waiting.isCancelled());
        server.msgQueue.addMessage(b, "new", 1, telemetry);
        assertEquals("new", server.msgQueue.retrieveMessage(b, telemetry).get());
        server.cleanupKey(telemetry);
    }
}
