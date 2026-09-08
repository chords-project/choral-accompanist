package choral.accompanist.examples.warehouse.loyalty.sidecar;

import choral.accompanist.faulttolerance.Transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

public interface LoyaltyTransactions extends choral.accompanist.examples.warehouse.choreograhpy.LoyaltyService {
    Set<Transaction> allTransactions();

    void createTables(Connection con) throws SQLException;
}
