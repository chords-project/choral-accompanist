package choral.accompanist;

import choral.accompanist.tracing.AccompanistTelemetry;
import choral.accompanist.tracing.TelemetrySession;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongUpDownCounter;

import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import choral.accompanist.faulttolerance.ReceiveTimeoutException;

public class MessageQueue<T> {
    /**
     * Maps a sessionID and a sender name to a queue of messages.
     */
    private final HashMap<Session, SessionQueue> queue = new HashMap<>();
    private final LongUpDownCounter queueSizeGauge;
    private final Duration timeout;

    public MessageQueue(OpenTelemetry openTelemetry) {
        this(Duration.ofSeconds(10), openTelemetry);
    }

    public MessageQueue(Duration timeout, OpenTelemetry openTelemetry) {
        this.timeout = timeout;
        queueSizeGauge = openTelemetry.getMeter(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME)
                .upDownCounterBuilder("accompanist.message-queue.size")
                .setUnit("messages")
                .setDescription("The size of the message queue")
                .build();
    }

    public synchronized void addMessage(Session session, T message, int sequenceNumber, TelemetrySession telemetrySession) {
        if (!queue.containsKey(session)) {
            queue.put(session, new SessionQueue(session.senderName(), telemetrySession));
            queueSizeGauge.add(1);
        }

        queue.get(session).addMessage(message, sequenceNumber);
    }

    public synchronized Future<T> retrieveMessage(Session session, TelemetrySession telemetrySession) {
        if (!queue.containsKey(session)) {
            queueSizeGauge.add(-1);
            queue.put(session, new SessionQueue(session.senderName(), telemetrySession));
        }

        return queue.get(session).retrieveNextMessage();
    }

    public synchronized void cleanupSession(Session session) {
        this.queue.entrySet().removeIf(entry -> {
            if (!entry.getKey().sessionID().equals(session.sessionID())) return false;
            entry.getValue().recv.values().forEach(future -> future.cancel(false));
            return true;
        });
    }

    private class SessionQueue {
        /**
         * The next receive operation will consume the first message in the queue.
         */
        private final HashMap<Integer, T> send = new HashMap<>();

        /**
         * The next message will be fed to the first future in the list.
         */
        private final HashMap<Integer, CompletableFuture<T>> recv = new HashMap<>();
        private int nextReceiveSequenceNumber = 1;
        private final TelemetrySession telemetrySession;
        private final String sender;

        private SessionQueue(String sender, TelemetrySession telemetrySession) {
            this.sender = sender;
            this.telemetrySession = telemetrySession;
        }

        public void addMessage(T message, int sequenceNumber) {
            if (recv.containsKey(sequenceNumber)) {
                telemetrySession.log("ReactiveServer message received: complete receive future");
                recv.get(sequenceNumber).complete(message);
            } else {
                telemetrySession.log("ReactiveServer message received: existing session, enqueue send");
                send.put(sequenceNumber, message);
            }
        }

        public Future<T> retrieveNextMessage() {
            int sequence = nextReceiveSequenceNumber;
            CompletableFuture<T> future = new CompletableFuture<T>()
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(error -> {
                        if (error instanceof java.util.concurrent.TimeoutException)
                            throw new ReceiveTimeoutException(sender, sequence, error);
                        throw new java.util.concurrent.CompletionException(error);
                    });

            if (send.containsKey(nextReceiveSequenceNumber)) {
                telemetrySession.log("ReactiveServer receive, message already arrived");
                T result = send.get(nextReceiveSequenceNumber);
                future.complete(result);
            } else {
                telemetrySession.log("ReactiveServer receive, waiting for message to arrive");
                recv.put(nextReceiveSequenceNumber, future);
            }

            nextReceiveSequenceNumber++;
            return future;
        }
    }
}
