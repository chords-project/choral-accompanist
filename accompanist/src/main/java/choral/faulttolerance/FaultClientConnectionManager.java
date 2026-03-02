package choral.faulttolerance;

import choral.reactive.connection.ClientConnectionManager;
import choral.reactive.connection.Message;
import io.opentelemetry.api.OpenTelemetry;

public interface FaultClientConnectionManager extends ClientConnectionManager {


    interface Factory {
        FaultClientConnectionManager makeConnectionManager(String address, ClientEvents events, OpenTelemetry telemetry) throws Exception;

        default ClientConnectionManager.Factory toNonFaultyFactory(ClientEvents events) {
            return (String address, OpenTelemetry telemetry) -> makeConnectionManager(address, events, telemetry);
        }
    }

    interface ClientEvents {
        void messageDeliveryConfirmed(Message message);

        void messageDeliveryFailed(Message message);
    }
}
