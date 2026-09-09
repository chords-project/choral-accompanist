package choral.accompanist.faulttolerance;

import choral.accompanist.connection.Message;
import choral.accompanist.tracing.FaultToleranceTelemetry;
import choral_reactive.ChannelGrpc;
import io.grpc.StatusRuntimeException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.api.OpenTelemetry;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One process-local owner of mailbox retry scheduling for a sidecar database.
 */
public final class MailboxRecoveryCoordinator implements AutoCloseable {
    private static final Map<DataSource, MailboxRecoveryCoordinator> SHARED = new WeakHashMap<>();

    public record Config(
            /* The timeout for gRPC message sends */
            Duration deadline,
            /* The periodic interval where the state of the database is checked and acted upon */
            Duration reconciliationInterval,
            Duration initialBackoff,
            Duration maxBackoff, int scanBatchSize, int maxConcurrentDeliveries,
            int maxConcurrentReplays, int cleanupBatchSize) {
        public static Config defaults() {
            return new Config(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(250),
                    Duration.ofSeconds(30), 128, 32, 8, 128);
        }
    }

    public interface ReplayHandler {
        void reconcileExecutions() throws Exception;
    }

    public static synchronized MailboxRecoveryCoordinator shared(SQLMailbox mailbox) {
        return SHARED.computeIfAbsent(mailbox.db, _ -> new MailboxRecoveryCoordinator(mailbox, Config.defaults()));
    }

    private final SQLMailbox mailbox;
    private final Config config;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("mailbox-recovery-scheduler").factory());
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore deliveries;
    private final ConcurrentMap<SQLMailbox.OutboxKey, Object> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DestinationState> destinations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean closed;
    private volatile ReplayHandler replayHandler;
    private volatile FaultToleranceTelemetry telemetry;

    MailboxRecoveryCoordinator(SQLMailbox mailbox, Config config) {
        this.mailbox = mailbox;
        this.config = config;
        this.deliveries = new Semaphore(config.maxConcurrentDeliveries());
    }

    public Config config() {
        return config;
    }

    public void setReplayHandler(ReplayHandler handler) {
        this.replayHandler = handler;
    }

    public SQLMailbox mailbox() {
        return mailbox;
    }

    /** Configure the process-local metrics used by initial deliveries and background retries. */
    public synchronized void configureTelemetry(OpenTelemetry openTelemetry, String serviceName) {
        if (telemetry == null) telemetry = new FaultToleranceTelemetry(openTelemetry, serviceName);
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(this::reconcileSafely, 0,
                    config.reconciliationInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Healthy first sends are persisted and dispatched here without waiting for a scan.
     */
    public void send(Message proposed, String destination, FaultClientConnectionManager.ClientEvents events) throws Exception {
        var proposedKey = new SQLMailbox.OutboxKey(proposed.session.sessionID(), proposed.session.senderName(), destination, proposed.sequenceNumber);
        Object reservation = new Object();
        if (inFlight.putIfAbsent(proposedKey, reservation) != null) return;
        try {
            var output = mailbox.prepareOutput(proposed, destination);
            if (output.acknowledged()) return;
            var state = destinations.computeIfAbsent(destination, ignored -> new DestinationState());
            if (System.nanoTime() < state.nextAttemptNanos) return;
            if (!deliveries.tryAcquire()) return;
            dispatch(output, events, reservation);
            reservation = null; // callback owns it
        } finally {
            if (reservation != null) inFlight.remove(proposedKey, reservation);
        }
    }

    public void wake() {
        if (!closed) scheduler.execute(this::reconcileSafely);
    }

    private void reconcileSafely() {
        if (closed) return;
        try {
            for (var output : mailbox.pendingOutputs(config.scanBatchSize())) {
                if (!deliveries.tryAcquire()) break;
                Object reservation = new Object();
                if (inFlight.putIfAbsent(output.key(), reservation) != null) {
                    deliveries.release();
                    continue;
                }
                var state = destinations.computeIfAbsent(output.destination(), ignored -> new DestinationState());
                if (System.nanoTime() < state.nextAttemptNanos) {
                    inFlight.remove(output.key(), reservation);
                    deliveries.release();
                    continue;
                }
                dispatch(output, null, reservation);
            }
            ReplayHandler handler = replayHandler;
            if (handler != null) handler.reconcileExecutions();
            mailbox.cleanupRegularMessages(config.cleanupBatchSize());
        } catch (Exception ignored) {
            // Durable rows are the retry queue; the next bounded reconciliation retries DB work.
        }
    }

    /**
     * Send a message over gRPC in a background virtual thread.
     *
     * @param output      the outbox message to send
     * @param events      where to send callback events
     * @param reservation the inFlight reservation token
     */
    private void dispatch(SQLMailbox.PreparedOutput output, FaultClientConnectionManager.ClientEvents events, Object reservation) {
        try {
            workers.submit(() -> {
                try {
                    if (telemetry != null)
                        telemetry.sendAttempt(output.message().session, output.destination());
                    ManagedChannel channel = channels.computeIfAbsent(output.destination(), this::newChannel);
                    var call = ChannelGrpc.newFutureStub(channel)
                            .withDeadlineAfter(config.deadline().toMillis(), TimeUnit.MILLISECONDS)
                            .sendMessage(output.message().toGrpcMessage());
                    call.get();
                    // Receipt is only announced after the ACK is durable locally.
                    mailbox.didDeliverMessage(output.message(), output.destination());
                    destinations.get(output.destination()).success();
                    if (telemetry != null) telemetry.confirmation(output.message().session);
                    if (events != null) events.messageDeliveryConfirmed(output.message());
                    wake();
                } catch (Exception error) {
                    if (telemetry != null)
                        telemetry.sendFailure(output.message().session, failureCategory(error));
                    // A local ACK-persistence failure is not evidence that the peer is down.
                    if (!(rootCause(error) instanceof java.sql.SQLException))
                        destinations.get(output.destination()).failure(config);
                    if (events != null) events.messageDeliveryFailed(output.message());
                } finally {
                    inFlight.remove(output.key(), reservation);
                    deliveries.release();
                }
            });
        } catch (RuntimeException rejected) {
            inFlight.remove(output.key(), reservation);
            deliveries.release();
            throw rejected;
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        // walk the parent cause until the root is located
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    static String failureCategory(Throwable error) {
        Throwable cause = rootCause(error);
        if (cause instanceof StatusRuntimeException statusError)
            return "grpc." + statusError.getStatus().getCode().name().toLowerCase();
        if (cause instanceof java.sql.SQLException) return "persistence";
        return cause.getClass().getSimpleName();
    }

    private ManagedChannel newChannel(String address) {
        try {
            URI uri = new URI(null, address, null, null, null).parseServerAuthority();
            InetSocketAddress socket = new InetSocketAddress(uri.getHost(), uri.getPort());
            return ManagedChannelBuilder.forAddress(socket.getHostString(), socket.getPort()).usePlaintext().build();
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        workers.shutdownNow();
        channels.values().forEach(ManagedChannel::shutdownNow);
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * State keeping track of when a message should attempt to be delivered again.
     */
    private static final class DestinationState {
        private int failures;
        private volatile long nextAttemptNanos;

        /**
         * Marks the state as succeeded
         */
        synchronized void success() {
            failures = 0;
            nextAttemptNanos = 0;
        }

        /**
         * Marks the state as failed
         */
        synchronized void failure(Config config) {
            failures = Math.min(failures + 1, 30);
            long base = config.initialBackoff().toNanos();
            long cap = config.maxBackoff().toNanos();
            long delay = Math.min(cap, base * (1L << Math.min(failures - 1, 20)));
            long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, delay / 4));
            nextAttemptNanos = System.nanoTime() + delay - delay / 8 + jitter;
        }
    }
}
