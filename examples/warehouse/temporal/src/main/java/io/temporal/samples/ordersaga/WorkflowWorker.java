package io.temporal.samples.ordersaga;

import io.temporal.samples.ordersaga.web.ServerInfo;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkflowWorker {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowWorker.class);

    @SuppressWarnings("CatchAndPrintStackTrace")
    public static void main(String[] args) throws Exception {

        final String TASK_QUEUE = ServerInfo.getWarehouseTaskQueue();

        // worker factory that can be used to create workers for specific task queues
        WorkerFactory factory = WorkerFactory.newInstance(TemporalClient.get());

        // register warehouse worker
        io.temporal.worker.Worker workflowWorker = factory.newWorker(TASK_QUEUE, TemporalClient.getWorkerOptions());
        workflowWorker.registerWorkflowImplementationTypes(WarehouseSagaImpl.class);

        // Start all workers created by this factory.
        factory.start();
        logger.info("Workflow worker started for task queues: {}", TASK_QUEUE);
    }
}
