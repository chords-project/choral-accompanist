package dev.chords.warehouse.loyalty.sidecar;

import choral.faulttolerance.Transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

public interface LoyaltyTransactions extends dev.chords.warehouse.choreograhpy.LoyaltyService {
    Set<Transaction> allTransactions();

    void createTables(Connection con) throws SQLException;
}
