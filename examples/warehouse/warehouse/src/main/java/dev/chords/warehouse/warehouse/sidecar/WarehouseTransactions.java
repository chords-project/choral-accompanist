package dev.chords.warehouse.warehouse.sidecar;

import choral.faulttolerance.Transaction;
import dev.chords.warehouse.choreograhpy.WarehouseService;

import java.util.Set;

public interface WarehouseTransactions extends WarehouseService {
    Set<Transaction> allTransactions();
}
