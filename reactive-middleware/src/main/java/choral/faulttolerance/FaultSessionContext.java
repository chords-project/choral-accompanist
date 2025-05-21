package choral.faulttolerance;

import choral.reactive.SessionContext;
import choral.reactive.tracing.TelemetrySession;

public class FaultSessionContext extends SessionContext {
    private FaultTolerantServer server;

    public FaultSessionContext(FaultTolerantServer server, TelemetrySession telemetrySession) {
        super(server, telemetrySession);
        this.server = server;
    }

    public void transaction(Transaction trans) {
    }
}
