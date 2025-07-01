package io.temporal.samples.ordersaga;

import com.zaxxer.hikari.HikariDataSource;
import io.temporal.failure.ApplicationFailure;

public class LoyaltyActivitiesImpl implements LoyaltyActivities {

    HikariDataSource db;
    public static final int userID = 100;

    public LoyaltyActivitiesImpl(HikariDataSource db) {
        this.db = db;
    }

    @Override
    public void awardPointsToCustomer() {
        System.out.println("awardPointsToCustomer");

        try (var con = db.getConnection()) {
            con.setAutoCommit(false);

            // Create user points row if not exists
            try (var stmt = con.prepareStatement("""
                    INSERT INTO loyalty_points (user_id, points) VALUES (?, 0) ON CONFLICT DO NOTHING;
                    """)) {
                stmt.setInt(1, userID);
                stmt.execute();
            }

            // Award loyalty points
            try (var stmt = con.prepareStatement("""
                    UPDATE loyalty_points SET points = points + 1 WHERE user_id = ?;
                    """)) {
                stmt.setInt(1, userID);
                stmt.execute();
            }

            con.commit();

        } catch (Exception e) {
            throw ApplicationFailure.newFailureWithCause("failed to award points to customer", e.getClass().getName(), e);
        }
    }

    @Override
    public void compensatePointsFromCustomer() {
        System.out.println("compensatePointsFromCustomer");

        try (var con = db.getConnection()) {
            try (var stmt = con.prepareStatement("""
                    UPDATE loyalty_points SET points = points - 1 WHERE user_id = ?;
                    """)) {
                stmt.setInt(1, userID);
                stmt.execute();
            }
        } catch (Exception e) {
            throw ApplicationFailure.newFailureWithCause("failed to compensate points from customer", e.getClass().getName(), e);
        }
    }
}
