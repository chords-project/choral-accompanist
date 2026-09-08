package choral.accompanist.examples.warehouse.payment.sidecar;

import choral.accompanist.faulttolerance.SQLTransaction;
import choral.accompanist.faulttolerance.Transaction;
import choral.accompanist.examples.warehouse.proto.PaymentGrpc;
import choral.accompanist.examples.warehouse.proto.PaymentOuterClass;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.sql.SQLException;
import java.util.Set;

public class SidecarTransactions implements PaymentTransactions {

    ManagedChannel channel;
    PaymentGrpc.PaymentBlockingStub blockingStub;

    public SidecarTransactions() {
        var address = System.getenv().getOrDefault("SERVICE_ADDRESS", "localhost");
        var port = Integer.parseInt(System.getenv().getOrDefault("SERVICE_PORT", "2000"));

        this.channel = ManagedChannelBuilder.forAddress(address, port).usePlaintext().build();
        this.blockingStub = PaymentGrpc.newBlockingStub(channel);
    }

    public Set<Transaction> allTransactions() {
        return Set.of(takeMoneyFromCustomer());
    }

    @Override
    public Transaction takeMoneyFromCustomer() {
        System.out.println("- Payment make transaction: takeMoneyFromCustomer");

        return new Transaction() {
            @Override
            public String transactionName() {
                return "takeMoneyFromCustomer";
            }

            @Override
            public boolean commit(int sessionID, SQLTransaction trans) throws SQLException {
                try {
                    var _ = blockingStub.commitTakeMoneyFromCustomer(PaymentOuterClass.Empty.newBuilder().build());
                    return true;
                } catch (StatusRuntimeException e) {
                    throw new SQLException(e);
                }
            }

            @Override
            public void compensate(int sessionID, SQLTransaction trans) throws SQLException {
                try {
                    var _ = blockingStub.compensateTakeMoneyFromCustomer(PaymentOuterClass.Empty.newBuilder().build());
                } catch (StatusRuntimeException e) {
                    throw new SQLException(e);
                }
            }
        };
    }
}
