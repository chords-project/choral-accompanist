package org.choral.accompanist.faulttolerance;

import org.choral.accompanist.Session;
import org.choral.accompanist.connection.ServerConnectionManager;
import org.choral.accompanist.tracing.TelemetrySession;
import io.opentelemetry.api.OpenTelemetry;

public interface FaultServerConnectionManager extends ServerConnectionManager {

    void recoverableSessionFailure(TelemetrySession telemetrySession) throws Exception;

    void broadcastSessionFailure(TelemetrySession telemetrySession) throws Exception;

    void sessionCompleted(TelemetrySession telemetrySession) throws Exception;

    static FaultServerConnectionManager.Factory defaultFactory() {
        return (serviceName, events, telemetry) -> new RMQChannelReceiver(serviceName, events);
    }

    interface Factory {
        FaultServerConnectionManager makeConnectionManager(String serviceName, FaultServerConnectionManager.ServerEvents events, OpenTelemetry telemetry);
    }

    interface ServerEvents extends ServerConnectionManager.ServerEvents {
        void sessionFailed(Session sessionID) throws Exception;
    }
}
