package choral.accompanist;

import choral.accompanist.faulttolerance.ReceiveTimeoutException;
import choral.accompanist.tracing.TelemetrySession;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class MessageQueueTimeoutTest {
    @Test void timeoutCarriesExactSenderAndSequence() {
        var queue = new MessageQueue<String>(Duration.ofMillis(10), OpenTelemetry.noop());
        var session = new Session("test", "peer", 12);
        var telemetry = TelemetrySession.makeNoop(session);
        var first = assertThrows(ExecutionException.class, () -> queue.retrieveMessage(session, telemetry).get());
        var timeout = assertInstanceOf(ReceiveTimeoutException.class, first.getCause());
        assertEquals("peer", timeout.sender());
        assertEquals(1, timeout.sequenceNumber());
    }
}
