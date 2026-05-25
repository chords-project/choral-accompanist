package choral.accompanist.examples.warehouse.loyalty;

import choral.accompanist.examples.warehouse.loyalty.service.LoyaltyService;
import choral.accompanist.examples.warehouse.loyalty.sidecar.LoyaltySidecar;

public class Main {
    public static void main(String[] args) throws Exception {
        switch (System.getenv().getOrDefault("RUN", "").trim().toLowerCase()) {
            case "sidecar":
                LoyaltySidecar.main(args);
                break;
            case "service":
                LoyaltyService.main(args);
                break;
            default:
                throw new IllegalArgumentException("Please specify RUN environment variable to be either 'sidecar' or 'service'");
        }
    }
}
