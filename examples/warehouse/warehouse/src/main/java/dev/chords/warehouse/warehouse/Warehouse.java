package dev.chords.warehouse.warehouse;

import choral.faulttolerance.FaultDataStore;
import choral.faulttolerance.FaultSessionContext;
import choral.faulttolerance.FaultTolerantServer;
import choral.faulttolerance.SqlDataStore;
import choral.reactive.Session;
import choral.reactive.tracing.TelemetrySession;
import com.rabbitmq.client.ConnectionFactory;
import dev.chords.warehouse.choreograhpy.WarehouseOrder_Warehouse;

import java.sql.DriverManager;

public class Warehouse implements FaultTolerantServer.FaultSessionEvent {

    public static void main(String[] args) throws Exception {
        var warehouse = new Warehouse();
        warehouse.start();
    }

    public static final String SERVICE_NAME = "WAREHOUSE";
    public static final String RMQ_ADDRESS = "localhost";

    protected final FaultTolerantServer server;
    protected final WarehouseService warehouseService;

    public Warehouse() throws Exception {
        var connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(RMQ_ADDRESS);
        var connection = connectionFactory.newConnection();

        var dbCon = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/warehouse_warehouse",
                "postgres",
                "postgres");
        FaultDataStore dataStore = new SqlDataStore(dbCon);

        server = new FaultTolerantServer(dataStore, connection, SERVICE_NAME, this);
        warehouseService = new WarehouseService();
    }

    public void start() throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                server.listen(RMQ_ADDRESS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(1000);

        orderFulfillment();
    }

    @Override
    public void onNewSession(FaultSessionContext ctx) {
        switch (ctx.session.choreographyName()) {
            case "WAREHOUSE_ORDER":
                WarehouseOrder_Warehouse chor = new WarehouseOrder_Warehouse(ctx, warehouseService);
                chor.orderFulfillment();
                break;
            default:
                throw new IllegalStateException("Unexpected session choreography: " + ctx.session.choreographyName());
        }
    }

    public void orderFulfillment() throws Exception {
        Session session = Session.makeSession("WAREHOUSE_ORDER", SERVICE_NAME);
        TelemetrySession telemetrySession = new TelemetrySession(session);

        server.invokeManualSession(telemetrySession);
    }
}
