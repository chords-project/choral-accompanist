package choral.accompanist.examples.warehouse.warehouse.sidecar;

import choral.accompanist.faulttolerance.SQLTransaction;
import choral.accompanist.faulttolerance.Transaction;

import java.sql.SQLException;
import java.util.Set;

public class DirectTransactions implements WarehouseTransactions {

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
                        INSERT INTO products (product_id, stock_quantity) VALUES (?, 1000000000) ON CONFLICT DO NOTHING;
                        """)) {
                    stmt.setInt(1, productID);
                    stmt.execute();
                }

                // PostgreSQL serializes updates to this row and rechecks the predicate.
                try (var stmt = trans.prepareStatement("UPDATE products SET stock_quantity = stock_quantity - 1 WHERE product_id = ? AND stock_quantity > 0;")) {
                    stmt.setInt(1, productID);
                    return stmt.executeUpdate() == 1;
                }
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
                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS fulfillment_capacity (
                              capacity_id INT PRIMARY KEY,
                              remaining_capacity INT NOT NULL CHECK (remaining_capacity >= 0)
                            );
                            INSERT INTO fulfillment_capacity (capacity_id, remaining_capacity)
                            VALUES (1, 1000000000) ON CONFLICT DO NOTHING;
                            """);
                }

                try (var stmt = trans.prepareStatement("""
                        UPDATE fulfillment_capacity SET remaining_capacity = remaining_capacity - 1
                        WHERE capacity_id = 1 AND remaining_capacity > 0;
                        """)) {
                    if (stmt.executeUpdate() != 1) return false;
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
                    if (stmt.executeUpdate() == 1) {
                        try (var capacity = trans.prepareStatement("""
                                UPDATE fulfillment_capacity SET remaining_capacity = remaining_capacity + 1
                                WHERE capacity_id = 1;
                                """)) {
                            capacity.executeUpdate();
                        }
                    }
                }
            }
        };
    }
}
