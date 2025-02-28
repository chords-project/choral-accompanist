package dev.chords.choreographies;

public class ServiceResources {
    public static final ServiceResources shared = new ServiceResources();

    private ServiceResources() {
    }

    public String frontend = System.getenv().getOrDefault("CHORAL_FRONTEND", "0.0.0.0:5401");
    public String cart = System.getenv().getOrDefault("CHORAL_CART", "0.0.0.0:5401");
    public String productCatalog = System.getenv().getOrDefault("CHORAL_PRODUCT_CATALOG", "0.0.0.0:5401");
    public String currency = System.getenv().getOrDefault("CHORAL_CURRENCY", "0.0.0.0:5401");
    public String payment = System.getenv().getOrDefault("CHORAL_PAYMENT", "0.0.0.0:5401");
    public String shipping = System.getenv().getOrDefault("CHORAL_SHIPPING", "0.0.0.0:5401");
    public String email = System.getenv().getOrDefault("CHORAL_EMAIL", "0.0.0.0:5401");
}
