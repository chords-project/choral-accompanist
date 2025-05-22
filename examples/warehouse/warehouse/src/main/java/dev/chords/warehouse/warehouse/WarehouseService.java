package dev.chords.warehouse.warehouse;

import choral.faulttolerance.Transaction;

public class WarehouseService implements dev.chords.warehouse.choreograhpy.WarehouseService {
    @Override
    public Transaction checkItemInStockAndReserveForOrder() {
        throw new RuntimeException("not implemented yet.");
    }

    @Override
    public Transaction packageAndSendOrder() {
        throw new RuntimeException("not implemented yet.");
    }
}
