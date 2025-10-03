package dev.chords.warehouse.warehouse;

import dev.chords.warehouse.warehouse.service.WarehouseService;
import dev.chords.warehouse.warehouse.sidecar.WarehouseSidecar;

public class Main {
    public static void main(String[] args) throws Exception {
        switch (System.getenv().getOrDefault("RUN", "").trim().toLowerCase()) {
            case "sidecar":
                WarehouseSidecar.main(args);
                break;
            case "service":
                WarehouseService.main(args);
                break;
            default:
                throw new IllegalArgumentException("Please specify RUN environment variable to be either 'sidecar' or 'service'");
        }
    }
}
