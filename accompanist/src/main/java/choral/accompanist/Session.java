package choral.accompanist;

import java.util.Random;
import java.io.Serializable;

/**
 * A unique identifier for an instance of a choreography at runtime.
 */
public class Session implements Serializable {

    protected final String choreographyID;
    protected final String sender; // TODO The sender should not be here, but some of the logic in ReactiveServer depends on it
    protected final Integer sessionID;
    /** Optional identity of the benchmark run that created this choreography. */
    protected final String benchmarkRunId;

    public Session(String choreographyID, String sender, Integer sessionID) {
        this(choreographyID, sender, sessionID, null);
    }

    public Session(String choreographyID, String sender, Integer sessionID, String benchmarkRunId) {
        this.choreographyID = choreographyID;
        this.sender = sender;
        this.sessionID = sessionID;
        this.benchmarkRunId = benchmarkRunId;
    }

    public static Session makeSession(String choreographyID, String sender) {
        return makeSession(choreographyID, sender, null);
    }

    public static Session makeSession(String choreographyID, String sender, String benchmarkRunId) {
        Random rand = new Random();
        return new Session(choreographyID, sender, Math.abs(rand.nextInt()), benchmarkRunId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;

        if (!(other instanceof Session))
            return false;

        Session that = (Session) other;
        return this.choreographyID.equals(that.choreographyID)
                && this.sessionID.equals(that.sessionID)
                && this.sender.equals(that.sender);
    }

    @Override
    public int hashCode() {
        return sessionID.hashCode() * 13 + sender.hashCode() * 3 * 13 + choreographyID.hashCode();
    }

    @Override
    public String toString() {
        return "Session [ " + choreographyName() + ", " + senderName() + ", " + sessionID + " ]";
    }

    public String choreographyName() {
        return choreographyID;
    }

    public String senderName() {
        return sender;
    }

    public Integer sessionID() {
        return sessionID;
    }

    public String benchmarkRunId() { return benchmarkRunId; }

    public Session replacingSender(String senderName) {
        return new Session(this.choreographyID, senderName, this.sessionID, this.benchmarkRunId);
    }
}
