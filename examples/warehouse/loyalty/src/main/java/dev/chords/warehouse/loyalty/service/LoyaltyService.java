package dev.chords.warehouse.loyalty.service;

import com.zaxxer.hikari.HikariDataSource;
import dev.chords.warehouse.proto.LoyaltyGrpc;
import dev.chords.warehouse.proto.LoyaltyOuterClass;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

public class LoyaltyService {
    private Server server;
    private HikariDataSource db;

    public LoyaltyService() throws SQLException {
        var dbUrl = System.getenv().getOrDefault("POSTGRES_URL", "postgresql://localhost:5432/warehouse_loyalty");
        this.db = new HikariDataSource();
        db.setJdbcUrl("jdbc:" + dbUrl);
        db.setUsername("postgres");
        db.setPassword("postgres");

        createTables();
    }

    protected void createTables() throws SQLException {
        // Create table if not exists
        try (var con = db.getConnection(); var stmt = con.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS loyalty_points (
                      user_id INT PRIMARY KEY,
                      points INT NOT NULL DEFAULT 0
                    );
                    """);
        }
    }

    public static void main(String[] args) throws Exception {
        var service = new LoyaltyService();
        var port = Integer.parseInt(System.getenv().getOrDefault("PORT", "2000"));
        service.start(port);
        service.blockUntilShutdown();
    }

    public void start(int port) throws IOException {
        server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(new LoyaltyImpl())
                .build()
                .start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may have been reset by its JVM shutdown
            // hook.
            System.err.println("GrpcServer: shutting down gRPC server since JVM is shutting down");
            try {
                LoyaltyService.this.stop();
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

    class LoyaltyImpl extends LoyaltyGrpc.LoyaltyImplBase {
        @Override
        public void commitAwardPointsToCustomer(LoyaltyOuterClass.UserID request, StreamObserver<LoyaltyOuterClass.Empty> responseObserver) {
            System.out.println("- Loyalty commit transaction: awardPointsToCustomer");

            try (var con = db.getConnection();) {
                con.setAutoCommit(false);

                // Create user points row if not exists
                try (var stmt = con.prepareStatement("""
                        INSERT INTO loyalty_points (user_id, points) VALUES (?, 0) ON CONFLICT DO NOTHING;
                        """)) {
                    stmt.setInt(1, request.getUserID());
                    stmt.execute();
                }

                // Award loyalty points
                try (var stmt = con.prepareStatement("""
                        UPDATE loyalty_points SET points = points + 1 WHERE user_id = ?;
                        """)) {
                    stmt.setInt(1, request.getUserID());
                    stmt.execute();
                }

                con.commit();

                responseObserver.onNext(LoyaltyOuterClass.Empty.newBuilder().build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(e);
            }
        }

        @Override
        public void compensateAwardPointsToCustomer(LoyaltyOuterClass.UserID request, StreamObserver<LoyaltyOuterClass.Empty> responseObserver) {
            try (var con = db.getConnection()) {
                con.setAutoCommit(false);

                try (var stmt = con.prepareStatement("""
                        UPDATE loyalty_points SET points = points - 1 WHERE user_id = ?;
                        """)) {
                    stmt.setInt(1, request.getUserID());
                    stmt.execute();
                }

                responseObserver.onNext(LoyaltyOuterClass.Empty.newBuilder().build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(e);
            }
        }
    }
}
