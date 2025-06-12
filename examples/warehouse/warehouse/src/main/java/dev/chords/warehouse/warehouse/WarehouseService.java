package dev.chords.warehouse.warehouse;

import choral.faulttolerance.SQLTransaction;
import choral.faulttolerance.Transaction;

import java.sql.SQLException;
import java.util.Set;

public class WarehouseService implements dev.chords.warehouse.choreograhpy.WarehouseService {

    public static final int userID = 100;
    public static final int productID = 123;

    public Set<Transaction> allTransactions() {
        return Set.of(checkItemInStockAndReserveForOrder(), packageAndSendOrder());
    }

    @Override
    public Transaction checkItemInStockAndReserveForOrder() {
        System.out.println("- Warehouse make transaction: checkItemInStockAndReserveForOrder");

        return new Transaction() {
            @Override
            public String transactionName() {
                return "checkItemInStockAndReserveForOrder";
            }

            @Override
            public boolean commit(int sessionID, SQLTransaction trans) throws SQLException {
                System.out.println("- Warehouse commit transaction: checkItemInStockAndReserveForOrder");

                // Create table if not exists
                try (var stmt = trans.createStatement()) {
                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS products (
                              product_id INT PRIMARY KEY,
                              stock_quantity INT NOT NULL DEFAULT 0
                            );
                            """);
                }

                // Create item row if not exists
                try (var stmt = trans.prepareStatement("""
                        INSERT INTO products (product_id, stock_quantity) VALUES (?, 5) ON CONFLICT DO NOTHING;
                        """)) {
                    stmt.setInt(1, productID);
                    stmt.execute();
                }

                // Check item in stock
                try (var stmt = trans.prepareStatement("SELECT * FROM products WHERE product_id = ?;")) {
                    stmt.setInt(1, productID);

                    try (var resultSet = stmt.executeQuery()) {
                        var foundRow = resultSet.next();
                        if (!foundRow) {
                            System.out.println("- FAILED: checkItemInStockAndReserveForOrder, item not found");
                            return false;
                        }

                        int stockQuantity = resultSet.getInt("stock_quantity");
                        if (stockQuantity <= 0) {
                            System.out.println("- FAILED: checkItemInStockAndReserveForOrder, item out of stock");
                            return false;
                        }
                    }
                }

                // Reduce item stock quantity
                try (var stmt = trans.prepareStatement("UPDATE products SET stock_quantity = stock_quantity - 1 WHERE product_id = ?;")) {
                    stmt.setInt(1, productID);
                    stmt.execute();
                }

                return true;
            }

            @Override
            public void compensate(int sessionID, SQLTransaction trans) throws SQLException {
                System.out.println("- Warehouse compensate transaction: checkItemInStockAndReserveForOrder");

                // Increase item stock quantity
                try (var stmt = trans.prepareStatement("UPDATE products SET stock_quantity = stock_quantity + 1 WHERE product_id = ?;")) {
                    stmt.setInt(1, productID);
                    stmt.execute();
                }
            }
        };
    }

    @Override
    public Transaction packageAndSendOrder() {
        System.out.println("- Warehouse make transaction: packageAndSendOrder");

        return new Transaction() {
            @Override
            public String transactionName() {
                return "packageAndSendOrder";
            }

            @Override
            public boolean commit(int sessionID, SQLTransaction trans) throws SQLException {
                System.out.println("- Warehouse commit transaction: packageAndSendOrder");

                // Create table if not exists
                try (var stmt = trans.createStatement()) {
                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS orders (
                              user_id INT,
                              session_id INT,
                              PRIMARY KEY (user_id, session_id)
                            );
                            """);
                }

                // Create order
                try (var stmt = trans.prepareStatement("""
                        INSERT INTO orders (user_id, session_id) VALUES (?, ?);
                        """)) {
                    stmt.setInt(1, userID);
                    stmt.setInt(2, sessionID);
                    stmt.execute();
                }

                return true;
            }

            @Override
            public void compensate(int sessionID, SQLTransaction trans) throws SQLException {
                System.out.println("- Warehouse compensate transaction: packageAndSendOrder");

                try (var stmt = trans.prepareStatement("DELETE FROM orders WHERE user_id = ? AND session_id = ?;")) {
                    stmt.setInt(1, userID);
                    stmt.setInt(2, sessionID);
                    stmt.execute();
                }
            }
        };
    }
}
