package choral.accompanist.faulttolerance;

/** Identifies the exact durable input on which a choreography attempt was waiting. */
public final class ReceiveTimeoutException extends RuntimeException {
    private final String sender;
    private final int sequenceNumber;

    public ReceiveTimeoutException(String sender, int sequenceNumber, Throwable cause) {
        super("Timed out waiting for " + sender + " sequence " + sequenceNumber, cause);
        this.sender = sender;
        this.sequenceNumber = sequenceNumber;
    }

    public String sender() { return sender; }
    public int sequenceNumber() { return sequenceNumber; }
}
