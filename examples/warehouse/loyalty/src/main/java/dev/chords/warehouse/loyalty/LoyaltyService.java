package dev.chords.warehouse.loyalty;

import choral.faulttolerance.SQLTransaction;
import choral.faulttolerance.Transaction;
import choral.reactive.Session;

import java.sql.Connection;
import java.sql.SQLException;

public class LoyaltyService implements dev.chords.warehouse.choreograhpy.LoyaltyService {

    public static final int userID = 100;

    public void createTables(Connection con) throws SQLException {
        System.out.println("Creating loyalty service tables...");

        // Create table if not exists
        try (var stmt = con.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS loyalty_points (
                      user_id INT PRIMARY KEY,
                      points INT NOT NULL DEFAULT 0
                    );
                    """);
        }
    }

    @Override
    public Transaction awardPointsToCustomer() {
        System.out.println("- Loyalty make transaction: awardPointsToCustomer");

        return new Transaction() {
            @Override
            public String transactionName() {
                return "awardPointsToCustomer";
            }

            @Override
            public boolean commit(Session session, SQLTransaction trans) throws SQLException {
                System.out.println("- Loyalty commit transaction: awardPointsToCustomer");

                // Create user points row if not exists
                try (var stmt = trans.prepareStatement("""
                        INSERT INTO loyalty_points (user_id, points) VALUES (?, 0) ON CONFLICT DO NOTHING;
                        """)) {
                    stmt.setInt(1, userID);
                    stmt.execute();
                }

                // Award loyalty points
                try (var stmt = trans.prepareStatement("""
                        UPDATE loyalty_points SET points = points + 1 WHERE user_id = ?;
                        """)) {
                    stmt.setInt(1, userID);
                    stmt.execute();
                }

                return true;
            }

            @Override
            public void compensate(Session session, SQLTransaction trans) throws SQLException {
                System.out.println("- Loyalty compensate transaction: awardPointsToCustomer");

                try (var stmt = trans.prepareStatement("""
                        UPDATE loyalty_points SET points = points - 1 WHERE user_id = ?;
                        """)) {
                    stmt.setInt(1, userID);
                    stmt.execute();
                }
            }
        };
    }
}
