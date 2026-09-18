package choral.accompanist.faulttolerance;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test void readinessWaitsThroughConnectionFailuresAndCoalescesNotifications() {
        var gate = new MailboxRecoveryCoordinator.ReadinessGate();
        var channel = new TestChannel();
        channel.transition(ConnectivityState.TRANSIENT_FAILURE);
        var wakes = new AtomicInteger();

        gate.await("payment", channel, wakes::incrementAndGet);
        gate.await("payment", channel, wakes::incrementAndGet);
        assertEquals(1, channel.resolverResets);
        assertEquals(0, wakes.get());
        assertTrue(channel.connectionRequested);
        assertEquals(ConnectivityState.CONNECTING, channel.state);

        channel.transition(ConnectivityState.TRANSIENT_FAILURE);
        assertEquals(0, wakes.get());
        channel.transition(ConnectivityState.CONNECTING);
        assertEquals(0, wakes.get());
        channel.transition(ConnectivityState.READY);
        assertEquals(1, wakes.get());
        assertEquals(1, channel.resolverResets);
        channel.transition(ConnectivityState.TRANSIENT_FAILURE);
        assertEquals(1, wakes.get());
    }

    @Test void alreadyConnectedChannelWakesImmediately() {
        var channel = new TestChannel();
        channel.transition(ConnectivityState.READY);
        var wakes = new AtomicInteger();
        new MailboxRecoveryCoordinator.ReadinessGate().await("payment", channel, wakes::incrementAndGet);
        assertEquals(1, wakes.get());
        assertEquals(0, channel.resolverResets);
    }

    @Test void connectionBecomingReadyDuringCallbackRegistrationIsNotMissed() {
        var channel = new TestChannel();
        channel.readyOnRegistration = true;
        var wakes = new AtomicInteger();
        new MailboxRecoveryCoordinator.ReadinessGate().await("payment", channel, wakes::incrementAndGet);
        assertEquals(1, wakes.get());
    }

    @Test void closingGateSuppressesPendingAndNewNotifications() {
        var gate = new MailboxRecoveryCoordinator.ReadinessGate();
        var channel = new TestChannel();
        var wakes = new AtomicInteger();
        gate.await("payment", channel, wakes::incrementAndGet);
        gate.close();
        channel.transition(ConnectivityState.READY);
        gate.await("payment", channel, wakes::incrementAndGet);
        assertEquals(0, wakes.get());
        assertEquals(1, channel.resolverResets);
    }

    @Test void channelShutdownDoesNotWakeDelivery() {
        var gate = new MailboxRecoveryCoordinator.ReadinessGate();
        var channel = new TestChannel();
        var wakes = new AtomicInteger();
        gate.await("payment", channel, wakes::incrementAndGet);
        channel.transition(ConnectivityState.SHUTDOWN);
        assertEquals(0, wakes.get());
    }

    @Test void repeatedReadinessDoesNotOverlapProbe() {
        var state = new MailboxRecoveryCoordinator.DestinationState();
        var failed = state.tryAcquire(CONFIG, 0);
        state.failure(failed, CONFIG);
        state.release(failed);
        state.ready();
        var probe = state.tryAcquire(CONFIG, 0);
        assertNotNull(probe);
        state.ready();
        assertNull(state.tryAcquire(CONFIG, Long.MAX_VALUE));
        state.failure(probe, CONFIG);
        state.release(probe);
        assertNull(state.tryAcquire(CONFIG, 0));
    }

    @Test void readinessPreservesHealthyConcurrency() {
        var state = new MailboxRecoveryCoordinator.DestinationState();
        state.ready();
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

    private static final class TestChannel extends ManagedChannel {
        private ConnectivityState state = ConnectivityState.IDLE;
        private Runnable callback;
        private int resolverResets;
        private boolean connectionRequested;
        private boolean readyOnRegistration;

        void transition(ConnectivityState next) {
            state = next;
            Runnable previous = callback;
            callback = null;
            if (previous != null) previous.run();
        }

        @Override public ConnectivityState getState(boolean requestConnection) {
            connectionRequested |= requestConnection;
            if (requestConnection && state == ConnectivityState.IDLE) state = ConnectivityState.CONNECTING;
            return state;
        }
        @Override public void notifyWhenStateChanged(ConnectivityState source, Runnable callback) {
            if (readyOnRegistration) state = ConnectivityState.READY;
            if (state != source) {
                callback.run();
                return;
            }
            assertNull(this.callback, "Only one connectivity callback may be pending");
            this.callback = callback;
        }
        @Override public void enterIdle() {
            resolverResets++;
            transition(ConnectivityState.IDLE);
        }
        @Override public void resetConnectBackoff() {
            throw new AssertionError("Recovery must reset the resolver, not just connection backoff");
        }
        @Override public ManagedChannel shutdown() { transition(ConnectivityState.SHUTDOWN); return this; }
        @Override public ManagedChannel shutdownNow() { return shutdown(); }
        @Override public boolean isShutdown() { return state == ConnectivityState.SHUTDOWN; }
        @Override public boolean isTerminated() { return isShutdown(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isShutdown(); }
        @Override public String authority() { return "payment"; }
        @Override public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
                MethodDescriptor<RequestT, ResponseT> method, CallOptions options) {
            throw new UnsupportedOperationException("Connectivity tests must not send RPCs");
        }
    }
}
