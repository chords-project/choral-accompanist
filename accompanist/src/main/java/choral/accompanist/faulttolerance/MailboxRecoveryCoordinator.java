package choral.accompanist.faulttolerance;

import choral.accompanist.connection.Message;
import choral.accompanist.tracing.FaultToleranceTelemetry;
import choral.accompanist.tracing.AccompanistTelemetry;
import choral.accompanist.tracing.HeaderTextMapGetter;
import choral.accompanist.tracing.Logger;
import choral.accompanist.tracing.TelemetrySession;
import choral_reactive.ChannelGrpc;
import choral_reactive.ChannelOuterClass;
import io.grpc.StatusRuntimeException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

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
            int maxConcurrentDeliveriesPerDestination, int maxConcurrentReplays, int cleanupBatchSize) {
        public static Config defaults() {
            return new Config(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(1),
                    Duration.ofSeconds(10),
                    positiveEnvironmentInt("ACCOMPANIST_SCAN_BATCH_SIZE", 128),
                    positiveEnvironmentInt("ACCOMPANIST_DELIVERY_CONCURRENCY", 32),
                    positiveEnvironmentInt("ACCOMPANIST_DELIVERY_CONCURRENCY", 8),
                    positiveEnvironmentInt("ACCOMPANIST_EXECUTION_CONCURRENCY", 8),
                    128);
        }

        private static int positiveEnvironmentInt(String name, int defaultValue) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) return defaultValue;
            try {
                int parsed = Integer.parseInt(value);
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                // Report the same actionable startup error for malformed and non-positive values.
            }
            throw new IllegalArgumentException(name + " must be a positive integer, but was: " + value);
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
    private final WakeThrottle wakeThrottle = new WakeThrottle();
    private volatile boolean closed;
    private volatile ReplayHandler replayHandler;
    private volatile FaultToleranceTelemetry telemetry;
    private volatile Logger logger;

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

    /**
     * Configure the process-local metrics used by initial deliveries and background retries.
     */
    public synchronized void configureTelemetry(OpenTelemetry openTelemetry, String serviceName) {
        if (telemetry == null) {
            telemetry = new FaultToleranceTelemetry(openTelemetry, serviceName);
            logger = new Logger(openTelemetry, MailboxRecoveryCoordinator.class.getName());
        }
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(this::wake, 0,
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
            if (!deliveries.tryAcquire()) return;
            var permit = state.tryAcquire(config, System.nanoTime());
            if (permit == null) {
                deliveries.release();
                return;
            }
            dispatch(output, events, reservation, state, permit);
            reservation = null; // callback owns it
        } finally {
            if (reservation != null) inFlight.remove(proposedKey, reservation);
        }
    }

    public void wake() {
        if (closed || !wakeThrottle.request()) return;
        try {
            scheduler.execute(this::runRequestedReconciliations);
        } catch (RuntimeException rejected) {
            wakeThrottle.cancel();
            if (!closed) throw rejected;
        }
    }

    private void runRequestedReconciliations() {
        do {
            reconcileSafely();
        } while (!closed && wakeThrottle.finishPass());
        if (closed) wakeThrottle.cancel();
    }

    /**
     * Announces that this process is accepting mailbox messages. Notifications use the same
     * cached gRPC channels as durable message delivery, but are deliberately not mailbox rows.
     */
    public void announceReady(String serviceName, String address, String[] peers) {
        if (peers == null) return;
        var notification = ChannelOuterClass.ReadyNotification.newBuilder()
                .setServiceName(serviceName)
                .setAddress(address)
                .build();
        for (String peer : peers) {
            if (peer == null || peer.isBlank() || peer.equals(address)) continue;
            try {
                workers.submit(() -> {
                    try {
                        ChannelGrpc.newFutureStub(channel(peer))
                                .withDeadlineAfter(config.deadline().toMillis(), TimeUnit.MILLISECONDS)
                                .notifyReady(notification)
                                .get();
                    } catch (Exception error) {
                        logReadinessFailure("Failed to notify peer that this service is ready", peer, error);
                        // Periodic reconciliation remains the fallback when a peer is unavailable.
                    }
                });
            } catch (RuntimeException error) {
                logReadinessFailure("Failed to schedule peer readiness notification", peer, error);
                // Shutdown can race with a best-effort readiness announcement.
            }
        }
    }

    private void logReadinessFailure(String message, String peer, Throwable error) {
        Logger configuredLogger = logger;
        if (configuredLogger == null) return;
        configuredLogger.warn(message, Attributes.builder()
                .put("peer.address", peer)
                .putAll(Logger.exceptionAttributes(error))
                .build());
    }

    /**
     * A peer has proven that it is currently reachable by delivering a readiness RPC.
     */
    public void destinationReady(String destination) {
        if (destination == null || destination.isBlank()) return;
        destinations.computeIfAbsent(destination, ignored -> new DestinationState()).ready();
        ManagedChannel channel = channels.get(destination);
        if (channel != null) channel.resetConnectBackoff();
        wake();
    }

    private void reconcileSafely() {
        if (closed) return;
        try {
            for (var output : mailbox.pendingOutputs(config.scanBatchSize())) {
                Object reservation = new Object();
                if (inFlight.putIfAbsent(output.key(), reservation) != null) {
                    continue;
                }
                var state = destinations.computeIfAbsent(output.destination(), ignored -> new DestinationState());
                if (!deliveries.tryAcquire()) {
                    inFlight.remove(output.key(), reservation);
                    break;
                }
                var permit = state.tryAcquire(config, System.nanoTime());
                if (permit == null) {
                    inFlight.remove(output.key(), reservation);
                    deliveries.release();
                    continue;
                }
                dispatch(output, null, reservation, state, permit);
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
    private void dispatch(SQLMailbox.PreparedOutput output, FaultClientConnectionManager.ClientEvents events,
                          Object reservation, DestinationState destinationState,
                          DestinationState.AttemptPermit permit) {
        try {
            workers.submit(() -> {
                try {
                    dispatchMessage(output, events, destinationState, permit);
                } finally {
                    inFlight.remove(output.key(), reservation);
                    destinationState.release(permit);
                    deliveries.release();
                    wake();
                }
            });
        } catch (RuntimeException rejected) {
            inFlight.remove(output.key(), reservation);
            destinationState.release(permit);
            deliveries.release();
            throw rejected;
        }
    }

    /**
     * Performs and traces one physical delivery attempt, including acknowledgement persistence.
     */
    private void dispatchMessage(SQLMailbox.PreparedOutput output,
                                 FaultClientConnectionManager.ClientEvents events,
                                 DestinationState destinationState,
                                 DestinationState.AttemptPermit permit) {
        FaultToleranceTelemetry configuredTelemetry = telemetry;
        Span span = createDeliveryAttemptSpan(configuredTelemetry, output);

        try (Scope ignored = span.makeCurrent()) {
            try {
                if (configuredTelemetry != null)
                    configuredTelemetry.sendAttempt(output.message().session, output.destination());
                ManagedChannel channel = channel(output.destination());
                var call = ChannelGrpc.newFutureStub(channel)
                        .withDeadlineAfter(config.deadline().toMillis(), TimeUnit.MILLISECONDS)
                        .sendMessage(output.message().toGrpcMessage());
                call.get();
                // Receipt is only announced after the ACK is durable locally.
                mailbox.didDeliverMessage(output.message(), output.destination());
                destinationState.success(permit);
                if (configuredTelemetry != null) configuredTelemetry.confirmation(output.message().session);
                if (events != null) events.messageDeliveryConfirmed(output.message());
            } catch (Exception error) {
                span.setStatus(StatusCode.ERROR, "Failed to deliver durable message");
                span.recordException(error, deliveryAttributes(output));
                if (configuredTelemetry != null)
                    configuredTelemetry.sendFailure(output.message().session, failureCategory(error));

                // A local ACK-persistence failure is not evidence that the peer is down.
                if (!(rootCause(error) instanceof java.sql.SQLException))
                    destinationState.failure(permit, config);
                if (events != null) events.messageDeliveryFailed(output.message());
            }
        } finally {
            span.end();
        }
    }

    private Span createDeliveryAttemptSpan(FaultToleranceTelemetry configuredTelemetry,
                                           SQLMailbox.PreparedOutput output) {
        if (configuredTelemetry == null) return Span.getInvalid();

        Message message = output.message();
        OpenTelemetry openTelemetry = configuredTelemetry.getTelemetry();
        Context parent = openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.root(), message, new HeaderTextMapGetter());
        var builder = openTelemetry.getTracer(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME)
                .spanBuilder("mailbox message delivery")
                .setParent(parent)
                .setSpanKind(SpanKind.PRODUCER)
                .setAllAttributes(TelemetrySession.commonAttributes(message.session))
                .setAllAttributes(deliveryAttributes(output));

        SpanContext senderContext = message.senderSpanContext == null
                ? SpanContext.getInvalid()
                : message.senderSpanContext.toSpanContext();
        if (senderContext.isValid()) builder.addLink(senderContext);
        return builder.startSpan();
    }

    private static Attributes deliveryAttributes(SQLMailbox.PreparedOutput output) {
        return Attributes.builder()
                .put("messaging.destination", output.destination())
                .put("messaging.sequence_number", output.message().sequenceNumber)
                .build();
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

    private ManagedChannel channel(String address) {
        return channels.computeIfAbsent(address, this::newChannel);
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
    static final class DestinationState {
        private int failures;
        private long nextAttemptNanos;
        private int activeDeliveries;
        private boolean probeInFlight;
        private long generation;

        record AttemptPermit(long generation, boolean probe) {}

        synchronized AttemptPermit tryAcquire(Config config, long now) {
            if (failures > 0) {
                if (now < nextAttemptNanos || probeInFlight) return null;
                probeInFlight = true;
                activeDeliveries++;
                return new AttemptPermit(generation, true);
            }
            if (activeDeliveries >= config.maxConcurrentDeliveriesPerDestination()) return null;
            activeDeliveries++;
            return new AttemptPermit(generation, false);
        }

        /**
         * Marks the state as succeeded
         */
        synchronized void success(AttemptPermit permit) {
            // A completed RPC is newer evidence than any failure that completed before it,
            // including a failure from another request in the same healthy batch.
            failures = 0;
            nextAttemptNanos = 0;
            probeInFlight = false;
            generation++;
        }

        /**
         * Marks the state as failed
         */
        synchronized void failure(AttemptPermit permit, Config config) {
            if (permit.generation() != generation) return;
            failures = Math.min(failures + 1, 30);
            long base = config.initialBackoff().toNanos();
            long cap = config.maxBackoff().toNanos();
            long delay = Math.min(cap, base * (1L << Math.min(failures - 1, 20)));
            long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, delay / 4));
            nextAttemptNanos = System.nanoTime() + delay - delay / 8 + jitter;
            probeInFlight = false;
            generation++;
        }

        synchronized void release(AttemptPermit permit) {
            activeDeliveries--;
            if (permit.probe() && permit.generation() == generation) probeInFlight = false;
        }

        synchronized void ready() {
            failures = 1;
            nextAttemptNanos = 0;
            probeInFlight = false;
            generation++;
        }
    }

    /**
     * Coalesces any number of wake requests received during a pass into one follow-up pass.
     */
    static final class WakeThrottle {
        private boolean active;
        private boolean pending;

        synchronized boolean request() {
            if (active) {
                pending = true;
                return false;
            }
            active = true;
            return true;
        }

        synchronized boolean finishPass() {
            if (pending) {
                pending = false;
                return true;
            }
            active = false;
            return false;
        }

        synchronized void cancel() {
            active = false;
            pending = false;
        }
    }
}
