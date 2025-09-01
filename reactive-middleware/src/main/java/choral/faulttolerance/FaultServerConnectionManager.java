package choral.faulttolerance;

import choral.reactive.connection.ServerConnectionManager;
import choral.reactive.tracing.TelemetrySession;
import io.opentelemetry.api.OpenTelemetry;

public interface FaultServerConnectionManager extends ServerConnectionManager {

    void recoverableSessionFailure(TelemetrySession telemetrySession) throws Exception;

    void broadcastSessionFailure(TelemetrySession telemetrySession) throws Exception;

    void sessionCompleted(TelemetrySession telemetrySession) throws Exception;

    static FaultServerConnectionManager makeConnectionManager(String serviceName, ServerEvents events, OpenTelemetry telemetry) {
        return new RMQChannelReceiver(serviceName, events);
    }

    interface ServerEvents extends ServerConnectionManager.ServerEvents {
        void sessionFailed(int sessionID) throws Exception;
    }
}
