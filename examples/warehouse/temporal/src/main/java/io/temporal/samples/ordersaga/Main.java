package io.temporal.samples.ordersaga;

import io.temporal.client.WorkflowStub;

import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws Exception {
        var workerType = System.getenv("WORKER_TYPE");
        if (workerType == null) {
            throw new IllegalArgumentException("WORKER_TYPE environment variable not set");
        }

        switch (workerType.toLowerCase().trim()) {
            case "loyalty":
                LoyaltyWorker.main(args);
                break;
            case "payment":
                PaymentWorker.main(args);
                break;
            case "warehouse":
                WarehouseWorker.main(args);
                break;
            case "endpoint":
                WarehouseCaller caller = new WarehouseCaller();
                RestEndpoint endpoint = new RestEndpoint(() -> {
                    var workflow = caller.runWorkflow();
                    String result = caller.client.newUntypedWorkflowStub(workflow.getWorkflowId()).getResult(String.class);
                    return "run workflow (%s): %s".formatted(workflow.getRunId(), result);
                });
                endpoint.start();
                break;
            default:
                throw new IllegalArgumentException("Unknown worker type: " + workerType);
        }
    }
}
