package io.temporal.samples.ordersaga;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface WarehouseSaga {
    @WorkflowMethod
    String orderFulfillment(int sessionID);
}
