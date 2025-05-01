package choral.reactive;

import static org.junit.jupiter.api.Assertions.*;

import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.TelemetrySession;
import io.opentelemetry.api.OpenTelemetry;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

public class ConnectionTest {

    @Test
    void testClientServerConnection() {
        class Stats {

            int newSessionCount = 0;
            int clientSessionID = -1;
            Session receivedSession = null;
            String firstMsg = null;
            String secondMsg = null;
        }

        Stats stats = new Stats();
        CountDownLatch done = new CountDownLatch(1);

        ReactiveServer server = new ReactiveServer("server", ctx -> {
            System.out.println("New session: " + ctx.session);
            stats.receivedSession = ctx.session;
            stats.newSessionCount++;

            assertDoesNotThrow(() -> {
                stats.firstMsg = ctx.chanB("client").<String>fcom().get();
                stats.secondMsg = ctx.chanB("client").<String>fcom().get();
            });

            done.countDown();
        });

        Thread.ofVirtual()
                .start(() -> {
                    assertDoesNotThrow(() -> {
                        server.listen("127.0.0.1:4567");
                    });
                });

        assertDoesNotThrow(() -> {
            try (ClientConnectionManager connManager = ClientConnectionManager.makeConnectionManager("127.0.0.1:4567",
                    OpenTelemetry.noop());) {

                Thread.sleep(1000);

                Session session = Session.makeSession("choreography", "client");
                ReactiveClient client = new ReactiveClient(connManager, "client",
                        TelemetrySession.makeNoop(session));

                stats.clientSessionID = session.sessionID;

                var chan = client.chanA(session);
                chan.fcom("hello");
                chan.fcom("world");

                // Wait for server to handle messages before closing
                boolean finished = done.await(5, TimeUnit.SECONDS);
                client.close();
                assertTrue(finished);
            } finally {
                server.close();
            }
        });

        assertEquals(1, stats.newSessionCount);
        assertEquals("choreography", stats.receivedSession.choreographyID);
        assertEquals("client", stats.receivedSession.sender);
        assertEquals(stats.clientSessionID, stats.receivedSession.sessionID);

        assertEquals("hello", stats.firstMsg);
        assertEquals("world", stats.secondMsg);
    }
}
