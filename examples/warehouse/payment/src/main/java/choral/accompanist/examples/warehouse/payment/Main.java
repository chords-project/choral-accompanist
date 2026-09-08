package choral.accompanist.examples.warehouse.payment;

import choral.accompanist.examples.warehouse.payment.service.PaymentService;
import choral.accompanist.examples.warehouse.payment.sidecar.PaymentSidecar;

public class Main {
    public static void main(String[] args) throws Exception {
        switch (System.getenv().getOrDefault("RUN", "").trim().toLowerCase()) {
            case "sidecar":
                PaymentSidecar.main(args);
                break;
            case "service":
                PaymentService.main(args);
                break;
            default:
                throw new IllegalArgumentException("Please specify RUN environment variable to be either 'sidecar' or 'service'");
        }
    }
}
