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
                    INSERT INTO products (product_id, stock_quantity) VALUES (?, 5) ON CONFLICT DO NOTHING;
                    """)) {
                stmt.setInt(1, productID);
                stmt.execute();
            }

            // Check item in stock
            try (var stmt = con.prepareStatement("SELECT * FROM products WHERE product_id = ?;")) {
                stmt.setInt(1, productID);

                try (var resultSet = stmt.executeQuery()) {
                    var foundRow = resultSet.next();
                    if (!foundRow) {
                        throw ApplicationFailure.newFailure("item not found", "warehouse.UserException");
                    }

                    int stockQuantity = resultSet.getInt("stock_quantity");
                    if (stockQuantity <= 0) {
                        throw ApplicationFailure.newFailure("item out of stock", "warehouse.UserException");
                    }
                }
            }

            // Reduce item stock quantity
            try (var stmt = con.prepareStatement("UPDATE products SET stock_quantity = stock_quantity - 1 WHERE product_id = ?;")) {
                stmt.setInt(1, productID);
                stmt.execute();
            }

            con.commit();

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
            // Create table if not exists
            try (var stmt = con.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS orders (
                          user_id INT,
                          session_id INT,
                          PRIMARY KEY (user_id, session_id)
                        );
                        """);
            }

            // Create order
            try (var stmt = con.prepareStatement("""
                    INSERT INTO orders (user_id, session_id) VALUES (?, ?);
                    """)) {
                stmt.setInt(1, userID);
                stmt.setInt(2, sessionID);
                stmt.execute();
            }
        } catch (Exception e) {
            throw ApplicationFailure.newFailureWithCause("database exception", e.getClass().getName(), e);
        }
    }

    @Override
    public void cancelDelivery(int sessionID) {
        System.out.println("cancelDelivery");

        try (
                var con = db.getConnection();
                var stmt = con.prepareStatement("DELETE FROM orders WHERE user_id = ? AND session_id = ?;")
        ) {
            stmt.setInt(1, userID);
            stmt.setInt(2, sessionID);
            stmt.execute();
        } catch (Exception e) {
            throw ApplicationFailure.newFailureWithCause("database exception", e.getClass().getName(), e);
        }
    }
}
