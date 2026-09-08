package choral.accompanist.faulttolerance;

import choral.accompanist.connection.Message;
import choral.accompanist.tracing.AccompanistTelemetry;
import choral.accompanist.tracing.Logger;
import choral.accompanist.tracing.FaultToleranceTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.SQLException;

public class MailboxFaultClientManager implements FaultClientConnectionManager {
    private final OpenTelemetry telemetry;
    private final Logger logger;
    private final String address;
    private final ClientEvents events;
    private final FaultToleranceTelemetry faultToleranceTelemetry;
    private final MailboxRecoveryCoordinator coordinator;

    public MailboxFaultClientManager(SQLMailbox mailbox, String address, ClientEvents events, OpenTelemetry telemetry) throws URISyntaxException, SQLException {
        this.address = address;
        this.telemetry = telemetry;
        this.logger = new Logger(telemetry, MailboxFaultClientManager.class.getName());
        this.events = events;
        this.faultToleranceTelemetry = new FaultToleranceTelemetry(telemetry, "client");
        this.coordinator = MailboxRecoveryCoordinator.shared(mailbox);
    }

    public static FaultClientConnectionManager.Factory factory(DataSource db) throws SQLException {
        SQLMailbox mailbox = new SQLMailbox(db);
        var coordinator = MailboxRecoveryCoordinator.shared(mailbox);
        return new FaultClientConnectionManager.Factory() {
            @Override public FaultClientConnectionManager makeConnectionManager(String address, ClientEvents events, OpenTelemetry telemetry) throws Exception {
                return new MailboxFaultClientManager(mailbox, address, events, telemetry);
            }
            @Override public MailboxRecoveryCoordinator recoveryCoordinator() { return coordinator; }
        };
    }

    @Override
    public Connection makeConnection() {
        logger.debug("Connect to gRPC server " + address);
        return new MailboxFaultClientManager.ClientConnection();
    }

    @Override
    public void close() throws IOException, InterruptedException {
        // Channels are shared and owned by the sidecar coordinator.
    }

    public class ClientConnection implements Connection {

        Span connectionSpan;

        private ClientConnection() {
            this.connectionSpan = telemetry.getTracer(AccompanistTelemetry.INSTRUMENTATION_SCOPE_NAME)
                    .spanBuilder("GRPCConnection: " + address)
                    .setAttribute("address", address)
                    .startSpan();
        }

        @Override
        public void sendMessage(Message msg) throws Exception {

            faultToleranceTelemetry.sendAttempt(msg.session, address, "physical");
            coordinator.send(msg, address, events);
        }

        @Override
        public void close() throws IOException {
            connectionSpan.end();
        }

        @Override
        public String toString() {
            return "GRPCConnection [ address=" + address + " ]";
        }
    }
}
