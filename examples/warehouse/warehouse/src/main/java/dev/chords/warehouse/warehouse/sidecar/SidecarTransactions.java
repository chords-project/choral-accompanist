package dev.chords.warehouse.warehouse.sidecar;

import choral.faulttolerance.SQLTransaction;
import choral.faulttolerance.Transaction;
import dev.chords.warehouse.proto.WarehouseGrpc;
import dev.chords.warehouse.proto.WarehouseOuterClass;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.sql.SQLException;
import java.util.Set;

public class SidecarTransactions implements WarehouseTransactions {
    public static final int userID = 100;
    public static final int productID = 123;

    ManagedChannel channel;
    WarehouseGrpc.WarehouseBlockingStub blockingStub;

    public SidecarTransactions() {
        var address = System.getenv().getOrDefault("SERVICE_ADDRESS", "localhost");
        var port = Integer.parseInt(System.getenv().getOrDefault("SERVICE_PORT", "2000"));

        this.channel = ManagedChannelBuilder.forAddress(address, port).usePlaintext().build();
        this.blockingStub = WarehouseGrpc.newBlockingStub(channel);
    }

    @Override
    public Set<Transaction> allTransactions() {
        return Set.of(checkItemInStockAndReserveForOrder(), packageAndSendOrder());
    }

    @Override
    public Transaction checkItemInStockAndReserveForOrder() {
        return new Transaction() {
            @Override
            public String transactionName() {
                return "checkItemInStockAndReserveForOrder";
            }

            @Override
            public boolean commit(int sessionID, SQLTransaction trans) throws SQLException {
                try {
                    var product = WarehouseOuterClass.Product.newBuilder().setProductID(productID).build();
                    var _ = blockingStub.commitCheckItemInStockAndReserveForOrder(product);
                    return true;
                } catch (StatusRuntimeException e) {
                    throw new SQLException(e);
                }
            }

            @Override
            public void compensate(int sessionID, SQLTransaction trans) throws SQLException {
                try {
                    var product = WarehouseOuterClass.Product.newBuilder().setProductID(productID).build();
                    var _ = blockingStub.compensateCheckItemInStockAndReserveForOrder(product);
                } catch (StatusRuntimeException e) {
                    throw new SQLException(e);
                }
            }
        };
    }

    @Override
    public Transaction packageAndSendOrder() {
        return new Transaction() {
            @Override
            public String transactionName() {
                return "packageAndSendOrder";
            }

            @Override
            public boolean commit(int sessionID, SQLTransaction trans) throws SQLException {
                try {
                    var req = WarehouseOuterClass.SendOrderReq.newBuilder()
                            .setUserID(userID).setSessionID(sessionID).build();
                    var _ = blockingStub.commitPackageAndSendOrder(req);
                    return true;
                } catch (StatusRuntimeException e) {
                    throw new SQLException(e);
                }
            }

            @Override
            public void compensate(int sessionID, SQLTransaction trans) throws SQLException {
                try {
                    var req = WarehouseOuterClass.SendOrderReq.newBuilder()
                            .setUserID(userID).setSessionID(sessionID).build();
                    var _ = blockingStub.compensatePackageAndSendOrder(req);
                } catch (StatusRuntimeException e) {
                    throw new SQLException(e);
                }
            }
        };
    }
}
