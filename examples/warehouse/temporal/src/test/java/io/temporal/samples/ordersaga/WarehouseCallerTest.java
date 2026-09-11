package io.temporal.samples.ordersaga;

import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseCallerTest {
    @Test
    void ordinaryRequestsStartWithFreshServerGeneratedIds() throws Exception {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            var caller = new WarehouseCaller(env.getWorkflowClient());
            var first = caller.runWorkflow(null, null);
            var second = caller.runWorkflow();
            assertTrue(first.getWorkflowId().startsWith("WarehouseSaga-"));
            assertNotEquals(first.getWorkflowId(), second.getWorkflowId());
            assertDoesNotThrow(() -> UUID.fromString(first.getWorkflowId().substring("WarehouseSaga-".length())));
            // Like Accompanist, a request ID alone does not select benchmark mode.
            var orphan = caller.runWorkflow(null, "ignored");
            assertTrue(orphan.getWorkflowId().startsWith("WarehouseSaga-"));
        }
    }

    @Test
    void benchmarkRequestsRetainCorrelationAndRequireValidIds() {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            var caller = new WarehouseCaller(env.getWorkflowClient());
            String runId = UUID.randomUUID().toString();
            String requestId = UUID.randomUUID().toString();
            var execution = caller.runWorkflow(runId, requestId);
            assertEquals("benchmark-" + runId + "-" + requestId, execution.getWorkflowId());
            assertThrows(IllegalArgumentException.class, () -> caller.runWorkflow(runId, null));
            assertThrows(IllegalArgumentException.class, () -> caller.runWorkflow(runId, "invalid"));
            assertThrows(IllegalArgumentException.class, () -> caller.runWorkflow("invalid", requestId));
        }
    }
}
