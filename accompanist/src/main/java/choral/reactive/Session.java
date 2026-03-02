package choral.reactive;

import java.util.Random;
import java.io.Serializable;

/**
 * A unique identifier for an instance of a choreography at runtime.
 */
public class Session implements Serializable {

    protected final String choreographyID;
    protected final String sender; // TODO The sender should not be here, but some of the logic in ReactiveServer depends on it
    protected final Integer sessionID;

    public Session(String choreographyID, String sender, Integer sessionID) {
        this.choreographyID = choreographyID;
        this.sender = sender;
        this.sessionID = sessionID;
    }

    public static Session makeSession(String choreographyID, String sender) {
        Random rand = new Random();
        return new Session(choreographyID, sender, Math.abs(rand.nextInt()));
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

    public Session replacingSender(String senderName) {
        return new Session(this.choreographyID, senderName, this.sessionID);
    }
}
