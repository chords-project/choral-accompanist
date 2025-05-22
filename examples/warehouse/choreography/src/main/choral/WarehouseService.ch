package dev.chords.warehouse.choreograhpy;

import choral.faulttolerance.Transaction;

public interface WarehouseService@A {
    Transaction@A checkItemInStockAndReserveForOrder();
    Transaction@A packageAndSendOrder();
}
