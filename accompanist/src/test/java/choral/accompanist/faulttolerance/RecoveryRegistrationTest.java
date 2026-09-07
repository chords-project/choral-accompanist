package choral.accompanist.faulttolerance;

import choral.accompanist.Session;
import choral.accompanist.connection.Message;
import choral.accompanist.tracing.TelemetrySession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryRegistrationTest {
    @Test void recoveredMessagesDoNotLaunchSecondExecution() throws Exception {
        var session = new Session("test", "sender", 42);
        var starts = new AtomicInteger();
        var restarts = new AtomicInteger();
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        FaultDataStore store = (FaultDataStore) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FaultDataStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "recoverStartedSessions" -> List.of(session);
                    case "restartSession" -> { restarts.incrementAndGet(); yield true; }
                    case "hasSessionCompleted", "startSession", "completeSession", "failSession", "commitTransaction" -> false;
                    default -> null;
                });
        FaultServerConnectionManager transport = (FaultServerConnectionManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{FaultServerConnectionManager.class},
                (proxy, method, args) -> null);
        class Server extends FaultTolerantServer {
            Server() {
                super(store, (address, events, telemetry) -> null, (name, events, telemetry) -> transport,
                        "test", ctx -> null);
            }
            @Override protected Object startNewSession(TelemetrySession telemetry) throws Exception {
                starts.incrementAndGet();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                finally { cleanupKey(telemetry.session); finished.countDown(); }
                return null;
            }
            synchronized boolean registered() {
                return knownSessionIDs.contains(42) && telemetrySessionMap.containsKey(42);
            }
        }
        var server = new Server();
        try {
            server.recoverStartedSessions();
            assertTrue(server.registered());
            server.messageReceived(new Message(session, "recovered", 1));
            server.recoverStartedSessions();
            server.messageDeliveryFailed(new Message(session, "late callback", 1));
            assertEquals(0, restarts.get());
        } finally { release.countDown(); }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(1, starts.get());
    }
}
