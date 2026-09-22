package choral.accompanist.examples.warehouse.warehouse.sidecar;

import choral.accompanist.faulttolerance.SQLTransaction;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Uses an isolated schema to check the real PostgreSQL row lock behavior. */
@EnabledIfEnvironmentVariable(named = "ACCOMPANIST_TEST_POSTGRES_URL", matches = ".+")
class StockReservationTest {
    @Test
    void concurrentReservationsNeverExceedStock() throws Exception {
        String url = System.getenv("ACCOMPANIST_TEST_POSTGRES_URL");
        String schema = "stock_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(url); var stmt = admin.createStatement()) {
            stmt.execute("CREATE SCHEMA " + schema);
        }
        try (var db = new HikariDataSource()) {
            db.setJdbcUrl(url);
            db.setSchema(schema);
            db.setMaximumPoolSize(16);
            try (var con = db.getConnection(); var stmt = con.createStatement()) {
                stmt.execute("CREATE TABLE products (product_id INT PRIMARY KEY, stock_quantity INT NOT NULL)");
                stmt.execute("INSERT INTO products VALUES (123, 4)");
            }
            var reservation = new DirectTransactions().checkItemInStockAndReserveForOrder();
            try (var workers = Executors.newFixedThreadPool(16)) {
                var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int i = 0; i < 16; i++) {
                    tasks.add(workers.submit(() -> {
                        try (var con = db.getConnection()) {
                            con.setAutoCommit(false);
                            boolean result = reservation.commit(0, new SQLTransaction(con));
                            con.commit();
                            return result;
                        }
                    }));
                }
                int successes = 0;
                for (var task : tasks) if (task.get()) successes++;
                assertEquals(4, successes);
            }
            try (var con = db.getConnection(); var stmt = con.createStatement();
                 var rows = stmt.executeQuery("SELECT stock_quantity FROM products WHERE product_id = 123")) {
                rows.next();
                assertEquals(0, rows.getInt(1));
            }
        } finally {
            try (var admin = DriverManager.getConnection(url); var stmt = admin.createStatement()) {
                stmt.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
