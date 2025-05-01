package choral.reactive;

import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.tracing.TelemetrySession;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ChannelSelectTest {

    public enum SelectValue {
        YES, NO
    }

    @Test
    void testChannelSelect() {
        class Stats {
            SelectValue label1 = null;
            SelectValue label2 = null;
        }

        Stats stats = new Stats();
        CountDownLatch done = new CountDownLatch(1);

        ReactiveServer server = new ReactiveServer("server", ctx -> {
            System.out.println("New session: " + ctx.session);
            stats.label1 = ctx.chanB("client").select();
            stats.label2 = ctx.chanB("client").select();
            System.out.println("Server received label");
            done.countDown();
        });

        Thread.ofVirtual()
                .start(() -> {
                    assertDoesNotThrow(() -> {
                        server.listen("0.0.0.0:4567");
                    });
                });

        assertDoesNotThrow(() -> {
            try (ClientConnectionManager connManager =
                         ClientConnectionManager.makeConnectionManager(
                                 "0.0.0.0:4567",
                                 OpenTelemetry.noop())
            ) {
                Session session = Session.makeSession("choreography", "client");
                ReactiveClient client = new ReactiveClient(connManager, "client",
                        TelemetrySession.makeNoop(session));

                var chan = client.chanA(session);
                chan.select(SelectValue.YES);
                chan.select(SelectValue.NO);

                // Wait for server to handle messages before closing
                boolean finished = done.await(5, TimeUnit.SECONDS);
                client.close();
                assertTrue(finished);
            } finally {
                server.close();
            }
        });

        assertEquals(SelectValue.YES, stats.label1);
        assertEquals(SelectValue.NO, stats.label2);
    }
}
