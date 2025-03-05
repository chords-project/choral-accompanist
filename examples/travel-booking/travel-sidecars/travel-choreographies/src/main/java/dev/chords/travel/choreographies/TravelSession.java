package dev.chords.travel.choreographies;

import choral.reactive.Session;

import java.util.Random;

public class TravelSession extends Session {

    public final Choreography choreography;
    public final Service service;

    public TravelSession(Session session) throws IllegalArgumentException {
        super(session.choreographyName(), session.senderName(), session.sessionID());

        choreography = Choreography.valueOf(session.choreographyName());
        service = Service.valueOf(session.senderName());
    }

    public TravelSession(Choreography choreography, Service service, Integer sessionID) {
        super(choreography.name(), service.name(), sessionID);
        this.choreography = choreography;
        this.service = service;
    }

    public static TravelSession makeSession(Choreography choreography, Service service) {
        Random rand = new Random();
        return new TravelSession(choreography, service, Math.abs(rand.nextInt()));
    }

    public enum Choreography {
        BOOK_TRAVEL
    }

    public enum Service {
        CLIENT, FLIGHT, GEO, RESERVATION
    }

    @Override
    public String toString() {
        return "TravelSession [ " + choreographyName() + ", " + senderName() + ", " + sessionID + " ]";
    }

    @Override
    public Session replacingSender(String senderName) {
        return new TravelSession(choreography, Service.valueOf(senderName), sessionID);
    }

}
