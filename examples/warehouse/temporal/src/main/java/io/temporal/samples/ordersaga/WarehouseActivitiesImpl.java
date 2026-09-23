package io.temporal.samples.ordersaga;

import com.zaxxer.hikari.HikariDataSource;
import io.temporal.failure.ApplicationFailure;

public class WarehouseActivitiesImpl implements WarehouseActivities {

    HikariDataSource db;

    public static final int userID = 100;
    public static final int productID = 123;

    public WarehouseActivitiesImpl(HikariDataSource db) {
        this.db = db;
    }

    @Override
    public void checkItemInStockAndReserveForOrder() {
        System.out.println("checkItemInStockAndReserveForOrder");

        try (var con = db.getConnection()) {
            con.setAutoCommit(false);

            // Create table if not exists
            try (var stmt = con.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS products (
                          product_id INT PRIMARY KEY,
                          stock_quantity INT NOT NULL DEFAULT 0
                        );
                        """);
            }

            // Create item row if not exists
            try (var stmt = con.prepareStatement("""
                    INSERT INTO products (product_id, stock_quantity) VALUES (?, 1000000000) ON CONFLICT DO NOTHING;
                    """)) {
                stmt.setInt(1, productID);
                stmt.execute();
            }

            try (var stmt = con.prepareStatement("UPDATE products SET stock_quantity = stock_quantity - 1 WHERE product_id = ? AND stock_quantity > 0;")) {
                stmt.setInt(1, productID);
                if (stmt.executeUpdate() != 1) {
                    throw ApplicationFailure.newFailure("item out of stock", "warehouse.UserException");
                }
            }

            con.commit();

        } catch (ApplicationFailure e) {
            throw e;
        } catch (Exception e) {
            throw ApplicationFailure.newFailureWithCause("database exception", e.getClass().getName(), e);
        }
    }

    @Override
    public void cancelOrderReservation() {
        System.out.println("cancelOrderReservation");

        try (var con = db.getConnection()) {
            // Increase item stock quantity
            try (var stmt = con.prepareStatement("UPDATE products SET stock_quantity = stock_quantity + 1 WHERE product_id = ?;")) {
                stmt.setInt(1, productID);
                stmt.execute();
            }
        } catch (Exception e) {
            throw ApplicationFailure.newFailureWithCause("database exception", e.getClass().getName(), e);
        }
    }

    @Override
    public void packageAndSendOrder(int sessionID) {
        System.out.println("packageAndSendOrder");

        try (var con = db.getConnection()) {
            con.setAutoCommit(false);
            // Create table if not exists
            try (var stmt = con.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS orders (
                          user_id INT,
                          session_id INT,
                          PRIMARY KEY (user_id, session_id)
                        );
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS fulfillment_capacity (
                          capacity_id INT PRIMARY KEY,
                          remaining_capacity INT NOT NULL CHECK (remaining_capacity >= 0)
                        );
                        INSERT INTO fulfillment_capacity (capacity_id, remaining_capacity)
                        VALUES (1, 1000000000) ON CONFLICT DO NOTHING;
                        """);
            }

            try (var stmt = con.prepareStatement("""
                    UPDATE fulfillment_capacity SET remaining_capacity = remaining_capacity - 1
                    WHERE capacity_id = 1 AND remaining_capacity > 0;
                    """)) {
                if (stmt.executeUpdate() != 1) {
                    throw ApplicationFailure.newFailure("no fulfillment capacity available", "warehouse.UserException");
                }
            }

            // Create order
            try (var stmt = con.prepareStatement("""
                    INSERT INTO orders (user_id, session_id) VALUES (?, ?);
                    """)) {
                stmt.setInt(1, userID);
                stmt.setInt(2, sessionID);
                stmt.execute();
            }
            con.commit();
        } catch (ApplicationFailure e) {
            throw e;
        } catch (Exception e) {
            throw ApplicationFailure.newFailureWithCause("database exception", e.getClass().getName(), e);
        }
    }

    @Override
    public void cancelDelivery(int sessionID) {
        System.out.println("cancelDelivery");

        try (var con = db.getConnection()) {
            con.setAutoCommit(false);
            try (var stmt = con.prepareStatement("DELETE FROM orders WHERE user_id = ? AND session_id = ?;")) {
                stmt.setInt(1, userID);
                stmt.setInt(2, sessionID);
                if (stmt.executeUpdate() == 1) {
                    try (var capacity = con.prepareStatement("""
                            UPDATE fulfillment_capacity SET remaining_capacity = remaining_capacity + 1
                            WHERE capacity_id = 1;
                            """)) {
                        capacity.executeUpdate();
                    }
                }
            }
            con.commit();
        } catch (Exception e) {
            throw ApplicationFailure.newFailureWithCause("database exception", e.getClass().getName(), e);
        }
    }
}
