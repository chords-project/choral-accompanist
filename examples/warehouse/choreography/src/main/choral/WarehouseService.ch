package dev.chords.warehouse.choreography;

import choral.faulttolerance.Transaction;

public interface WarehouseService@A {
    Transaction@A checkItemInStockAndReserveForOrder();
    Transaction@A packageAndSendOrder();
}
