package choral.accompanist.examples.warehouse.warehouse.service;

import choral.accompanist.faulttolerance.SQLTransaction;
import choral.accompanist.faulttolerance.Transaction;
import com.zaxxer.hikari.HikariDataSource;
import choral.accompanist.examples.warehouse.proto.WarehouseGrpc;
import choral.accompanist.examples.warehouse.proto.WarehouseOuterClass;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

public class WarehouseService {

    private Server server;
    private HikariDataSource db;

    public WarehouseService() throws SQLException {
        var dbUrl = System.getenv().getOrDefault("POSTGRES_URL", "postgresql://localhost:5432/warehouse_loyalty");
        this.db = new HikariDataSource();
        db.setJdbcUrl("jdbc:" + dbUrl);
        db.setUsername("postgres");
        db.setPassword("postgres");
        db.setMaximumPoolSize(positiveEnvironmentInt("ACCOMPANIST_DB_POOL_SIZE", 10));

        createTables();
    }

    private static int positiveEnvironmentInt(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(name + " must be a positive integer, but was: " + value);
    }

    protected void createTables() throws SQLException {
        // Create table if not exists
        try (var con = db.getConnection(); var stmt = con.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS products (
                      product_id INT PRIMARY KEY,
                      stock_quantity INT NOT NULL DEFAULT 0
                    );
                    """);

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
    }

    public static void main(String[] args) throws Exception {
        var service = new WarehouseService();
        var port = Integer.parseInt(System.getenv().getOrDefault("PORT", "2000"));
        service.start(port);
        service.blockUntilShutdown();
    }

    public void start(int port) throws IOException {
        server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(new WarehouseImpl())
                .build()
                .start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may have been reset by its JVM shutdown
            // hook.
            System.err.println("GrpcServer: shutting down gRPC server since JVM is shutting down");
            try {
                WarehouseService.this.stop();
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
            }
            System.err.println("GrpcServer: server shut down");
        }));
    }

    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon
     * threads.
     */
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    class WarehouseImpl extends WarehouseGrpc.WarehouseImplBase {

        void commitCheckItemInStockAndReserveForOrderAction(int productID) throws Exception {
            try (var trans = db.getConnection()) {
                trans.setAutoCommit(false);
                System.out.println("- Warehouse commit transaction: checkItemInStockAndReserveForOrder");

                // Create item row if not exists
                try (var stmt = trans.prepareStatement("""
                        INSERT INTO products (product_id, stock_quantity) VALUES (?, 1000000000) ON CONFLICT DO NOTHING;
                        """)) {
                    stmt.setInt(1, productID);
                    stmt.execute();
                }

                try (var stmt = trans.prepareStatement("UPDATE products SET stock_quantity = stock_quantity - 1 WHERE product_id = ? AND stock_quantity > 0;")) {
                    stmt.setInt(1, productID);
                    if (stmt.executeUpdate() != 1) {
                        throw new Exception("item out of stock");
                    }
                }

                trans.commit();
            }
        }

        @Override
        public void commitCheckItemInStockAndReserveForOrder(WarehouseOuterClass.Product request, StreamObserver<WarehouseOuterClass.Empty> responseObserver) {
            try {
                commitCheckItemInStockAndReserveForOrderAction(request.getProductID());
            } catch (Exception e) {
                responseObserver.onError(e);
                return;
            }
            responseObserver.onNext(WarehouseOuterClass.Empty.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void compensateCheckItemInStockAndReserveForOrder(WarehouseOuterClass.Product request, StreamObserver<WarehouseOuterClass.Empty> responseObserver) {
            System.out.println("- Warehouse compensate transaction: checkItemInStockAndReserveForOrder");

            try (var trans = db.getConnection()) {
                trans.setAutoCommit(false);

                // Increase item stock quantity
                try (var stmt = trans.prepareStatement("UPDATE products SET stock_quantity = stock_quantity + 1 WHERE product_id = ?;")) {
                    stmt.setInt(1, request.getProductID());
                    stmt.execute();
                }

                trans.commit();
            } catch (Exception e) {
                responseObserver.onError(e);
                return;
            }

            responseObserver.onNext(WarehouseOuterClass.Empty.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void commitPackageAndSendOrder(WarehouseOuterClass.SendOrderReq request, StreamObserver<WarehouseOuterClass.Empty> responseObserver) {
            System.out.println("- Warehouse commit transaction: packageAndSendOrder");

            try (var trans = db.getConnection()) {
                trans.setAutoCommit(false);

                try (var stmt = trans.prepareStatement("""
                        UPDATE fulfillment_capacity SET remaining_capacity = remaining_capacity - 1
                        WHERE capacity_id = 1 AND remaining_capacity > 0;
                        """)) {
                    if (stmt.executeUpdate() != 1) {
                        throw new Exception("no fulfillment capacity available");
                    }
                }

                // Create order
                try (var stmt = trans.prepareStatement("""
                        INSERT INTO orders (user_id, session_id) VALUES (?, ?);
                        """)) {
                    stmt.setInt(1, request.getUserID());
                    stmt.setInt(2, request.getSessionID());
                    stmt.execute();
                }
                trans.commit();
            } catch (Exception e) {
                responseObserver.onError(e);
                return;
            }

            responseObserver.onNext(WarehouseOuterClass.Empty.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void compensatePackageAndSendOrder(WarehouseOuterClass.SendOrderReq request, StreamObserver<WarehouseOuterClass.Empty> responseObserver) {
            System.out.println("- Warehouse compensate transaction: packageAndSendOrder");

            try (var trans = db.getConnection()) {
                trans.setAutoCommit(false);
                try (var stmt = trans.prepareStatement("DELETE FROM orders WHERE user_id = ? AND session_id = ?;")) {
                    stmt.setInt(1, request.getUserID());
                    stmt.setInt(2, request.getSessionID());
                    if (stmt.executeUpdate() == 1) {
                        try (var capacity = trans.prepareStatement("""
                                UPDATE fulfillment_capacity SET remaining_capacity = remaining_capacity + 1
                                WHERE capacity_id = 1;
                                """)) {
                            capacity.executeUpdate();
                        }
                    }
                }
                trans.commit();
            } catch (Exception e) {
                responseObserver.onError(e);
                return;
            }

            responseObserver.onNext(WarehouseOuterClass.Empty.newBuilder().build());
            responseObserver.onCompleted();
        }
    }
}
