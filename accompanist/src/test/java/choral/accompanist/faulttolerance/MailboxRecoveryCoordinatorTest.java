package choral.accompanist.faulttolerance;

import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailboxRecoveryCoordinatorTest {
    private static final MailboxRecoveryCoordinator.Config CONFIG = new MailboxRecoveryCoordinator.Config(
            Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(30),
            128, 32, 2, 8, 128);

    @Test void categorizesGrpcFailuresByStatusCode() {
        var error = new ExecutionException(Status.UNAVAILABLE.asRuntimeException());

        assertEquals("grpc.unavailable", MailboxRecoveryCoordinator.failureCategory(error));
    }

    @Test void categorizesAckPersistenceFailures() {
        var error = new ExecutionException(new SQLException("database unavailable"));

        assertEquals("persistence", MailboxRecoveryCoordinator.failureCategory(error));
    }

    @Test void fallsBackToTheRootExceptionType() {
        var error = new ExecutionException(new IllegalStateException("unexpected"));

        assertEquals("IllegalStateException", MailboxRecoveryCoordinator.failureCategory(error));
    }

    @Test void limitsHealthyConcurrencyPerDestination() {
        var state = new MailboxRecoveryCoordinator.DestinationState();

        var first = state.tryAcquire(CONFIG, 0);
        var second = state.tryAcquire(CONFIG, 0);

        assertNotNull(first);
        assertNotNull(second);
        assertNull(state.tryAcquire(CONFIG, 0));
        state.release(first);
        assertNotNull(state.tryAcquire(CONFIG, 0));
    }

    @Test void concurrentFailuresAdvanceOnlyOneBackoffRound() {
        var state = new MailboxRecoveryCoordinator.DestinationState();
        var first = state.tryAcquire(CONFIG, 0);
        var second = state.tryAcquire(CONFIG, 0);

        state.failure(first, CONFIG);
        state.failure(second, CONFIG);
        state.release(first);
        state.release(second);

        assertNull(state.tryAcquire(CONFIG, 0));
        var probe = state.tryAcquire(CONFIG, Long.MAX_VALUE);
        assertNotNull(probe);
        assertNull(state.tryAcquire(CONFIG, Long.MAX_VALUE));
    }

    @Test void readyNotificationAllowsOneImmediateProbe() {
        var state = new MailboxRecoveryCoordinator.DestinationState();
        var failed = state.tryAcquire(CONFIG, 0);
        state.failure(failed, CONFIG);
        state.release(failed);

        state.ready();

        assertNotNull(state.tryAcquire(CONFIG, 0));
        assertNull(state.tryAcquire(CONFIG, 0));
    }

    @Test void successfulConcurrentDeliveryClearsFailureBackoff() {
        var state = new MailboxRecoveryCoordinator.DestinationState();
        var failed = state.tryAcquire(CONFIG, 0);
        var succeeded = state.tryAcquire(CONFIG, 0);

        state.failure(failed, CONFIG);
        state.success(succeeded);
        state.release(failed);
        state.release(succeeded);

        assertNotNull(state.tryAcquire(CONFIG, 0));
        assertNotNull(state.tryAcquire(CONFIG, 0));
    }

    @Test void coalescesRepeatedWakeRequestsIntoOneFollowUpPass() {
        var throttle = new MailboxRecoveryCoordinator.WakeThrottle();

        assertTrue(throttle.request());
        assertFalse(throttle.request());
        assertFalse(throttle.request());
        assertTrue(throttle.finishPass());
        assertFalse(throttle.finishPass());
    }

    @Test void acceptsANewWakeAfterReconciliationBecomesIdle() {
        var throttle = new MailboxRecoveryCoordinator.WakeThrottle();

        assertTrue(throttle.request());
        assertFalse(throttle.finishPass());
        assertTrue(throttle.request());
    }

}
