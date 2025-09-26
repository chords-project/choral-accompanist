package dev.chords.warehouse.loyalty.sidecar;

import choral.faulttolerance.SQLTransaction;
import choral.faulttolerance.Transaction;
import dev.chords.warehouse.proto.LoyaltyGrpc;
import dev.chords.warehouse.proto.LoyaltyOuterClass;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

public class SidecarTransactions implements LoyaltyTransactions {

    public static final int userID = 100;

    ManagedChannel channel;
    LoyaltyGrpc.LoyaltyBlockingStub blockingStub;

    public SidecarTransactions() {
        var address = System.getenv().getOrDefault("SERVICE_ADDRESS", "localhost");
        var port = Integer.parseInt(System.getenv().getOrDefault("SERVICE_PORT", "2000"));

        this.channel = ManagedChannelBuilder.forAddress(address, port).usePlaintext().build();
        this.blockingStub = LoyaltyGrpc.newBlockingStub(channel);
    }

    @Override
    public Set<Transaction> allTransactions() {
        return Set.of(awardPointsToCustomer());
    }

    @Override
    public void createTables(Connection con) throws SQLException {
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
            public boolean commit(int sessionID, SQLTransaction trans) throws SQLException {
                System.out.println("- Loyalty commit transaction: awardPointsToCustomer");

                try {
                    var user = LoyaltyOuterClass.UserID.newBuilder().setUserID(userID).build();
                    var _ = blockingStub.commitAwardPointsToCustomer(user);
                } catch (StatusRuntimeException e) {
                    throw new SQLException(e);
                }

                return true;
            }

            @Override
            public void compensate(int sessionID, SQLTransaction trans) throws SQLException {
                System.out.println("- Loyalty compensate transaction: awardPointsToCustomer");

                try {
                    var user = LoyaltyOuterClass.UserID.newBuilder().setUserID(userID).build();
                    var _ = blockingStub.compensateAwardPointsToCustomer(user);
                } catch (StatusRuntimeException e) {
                    throw new SQLException(e);
                }
            }
        };
    }
}
