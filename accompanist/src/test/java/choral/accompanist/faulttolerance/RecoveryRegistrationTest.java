package choral.accompanist.faulttolerance;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RecoveryRegistrationTest {
    @Test void faultTolerantServerRejectsTransportWithoutMailboxRecovery() {
        FaultDataStore store = (FaultDataStore) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FaultDataStore.class}, (proxy, method, args) -> null);
        FaultClientConnectionManager.Factory client = (address, events, telemetry) -> null;
        FaultServerConnectionManager.Factory server = (name, events, telemetry) ->
                (FaultServerConnectionManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{FaultServerConnectionManager.class}, (proxy, method, args) -> null);

        assertThrows(NullPointerException.class,
                () -> new FaultTolerantServer(store, client, server, "test", ctx -> null));
    }
}
