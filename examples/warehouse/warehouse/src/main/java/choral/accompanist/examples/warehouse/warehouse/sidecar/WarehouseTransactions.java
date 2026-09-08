package choral.accompanist.examples.warehouse.warehouse.sidecar;

import choral.accompanist.faulttolerance.Transaction;
import choral.accompanist.examples.warehouse.choreograhpy.WarehouseService;

import java.util.Set;

public interface WarehouseTransactions extends WarehouseService {
    Set<Transaction> allTransactions();
}
