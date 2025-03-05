package dev.chords.travel.choreographies;

public class ServiceResources {
    public static final ServiceResources shared = new ServiceResources();

    private ServiceResources() {
    }

    public String client = System.getenv().getOrDefault("CHORAL_CLIENT", "0.0.0.0:5401");
    public String flight = System.getenv().getOrDefault("CHORAL_FLIGHT", "0.0.0.0:5401");
    public String geo = System.getenv().getOrDefault("CHORAL_GEO", "0.0.0.0:5401");
    public String reservation = System.getenv().getOrDefault("CHORAL_RESERVATION", "0.0.0.0:5401");
}
