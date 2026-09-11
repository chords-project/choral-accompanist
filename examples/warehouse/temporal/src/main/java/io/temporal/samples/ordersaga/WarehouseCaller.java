/*
 *  Copyright (c) 2020 Temporal Technologies, Inc. All Rights Reserved
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.samples.ordersaga;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.samples.ordersaga.web.ServerInfo;

import javax.net.ssl.SSLException;
import java.io.FileNotFoundException;
import java.util.Random;
import java.util.UUID;

public class WarehouseCaller {

    public WorkflowClient client;
    final String WAREHOUSE_TASK_QUEUE = ServerInfo.getWarehouseTaskQueue();

    public WarehouseCaller() throws FileNotFoundException, SSLException {
        this(TemporalClient.get());
    }

    WarehouseCaller(WorkflowClient client) {
        this.client = client;
    }

    public WorkflowExecution runWorkflow() throws FileNotFoundException, SSLException {
        return runWorkflow(null, null);
    }

    public WorkflowExecution runWorkflow(String benchmarkRunId, String requestId) {
        String workflowId;
        if (benchmarkRunId == null) {
            workflowId = "WarehouseSaga-" + UUID.randomUUID();
        } else {
            UUID.fromString(benchmarkRunId);
            if (requestId == null) {
                throw new IllegalArgumentException("X-Benchmark-Request-Id is required with X-Benchmark-Run-Id");
            }
            UUID.fromString(requestId);
            workflowId = "benchmark-" + benchmarkRunId + "-" + requestId;
        }

        WorkflowOptions options =
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setWorkflowIdReusePolicy(io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .setTaskQueue(WAREHOUSE_TASK_QUEUE)
                        .build();
        WarehouseSaga workflow = client.newWorkflowStub(WarehouseSaga.class, options);

        Random rand = new Random();
        int sessionID = rand.nextInt();

        // start the workflow
        return WorkflowClient.start(workflow::orderFulfillment, sessionID);
    }

    @SuppressWarnings("CatchAndPrintStackTrace")
    public static void main(String[] args) throws Exception {
        new WarehouseCaller().runWorkflow();
        System.exit(0);
    }
}
