package dev.chords.choreographies;

import java.util.Random;

import choral.reactive.Session;

public class WebshopSession extends Session {

    public final Choreography choreography;
    public final Service service;

    public WebshopSession(Session session) throws IllegalArgumentException {
        super(session.choreographyName(), session.senderName(), session.sessionID());

        choreography = Choreography.valueOf(session.choreographyName());
        service = Service.valueOf(session.senderName());
    }

    public WebshopSession(Choreography choreography, Service service, Integer sessionID) {
        super(choreography.name(), service.name(), sessionID);
        this.choreography = choreography;
        this.service = service;
    }

    public static WebshopSession makeSession(Choreography choreography, Service service) {
        Random rand = new Random();
        return new WebshopSession(choreography, service, Math.abs(rand.nextInt()));
    }

    public enum Choreography {
        ADD_CART_ITEM, GET_CART_ITEMS, PLACE_ORDER;
    }

    public enum Service {
        CART, CURRENCY, FRONTEND, PAYMENT, PRODUCT_CATALOG, SHIPPING, EMAIL;
    }

    @Override
    public String toString() {
        return "WebshopSession [ " + choreographyName() + ", " + senderName() + ", " + sessionID + " ]";
    }

    @Override
    public Session replacingSender(String senderName) {
        return new WebshopSession(choreography, Service.valueOf(senderName), sessionID);
    }

}
