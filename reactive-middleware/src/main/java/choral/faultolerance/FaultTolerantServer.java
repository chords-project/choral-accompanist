package choral.faultolerance;

import choral.reactive.ReactiveServer;
import com.rabbitmq.client.Connection;
import io.opentelemetry.api.OpenTelemetry;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

public class FaultTolerantServer extends ReactiveServer {

    public FaultTolerantServer(Connection connection, String serviceName, OpenTelemetry telemetry, NewSessionEvent newSessionEvent) throws IOException, TimeoutException {
        super(serviceName, null, telemetry, Duration.ofMinutes(10), newSessionEvent);
        this.connectionManager = new RMQChannelReceiver(connection, serviceName, this);
    }

    public FaultTolerantServer(Connection connection, String serviceName, NewSessionEvent newSessionEvent) throws IOException, TimeoutException {
        this(connection, serviceName, OpenTelemetry.noop(), newSessionEvent);
    }
}
