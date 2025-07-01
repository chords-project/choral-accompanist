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

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.samples.ordersaga.web.ServerInfo;

import javax.net.ssl.SSLException;
import java.io.FileNotFoundException;
import java.util.concurrent.TimeUnit;

public class WarehouseCaller {

    public static void runWorkflow() throws FileNotFoundException, SSLException {
        // generate a random reference number

        // Workflow execution code

        WorkflowClient client = TemporalClient.get();
        final String WAREHOUSE_TASK_QUEUE = ServerInfo.getWarehouseTaskQueue();

        // get java timestamp
        long javaTime = System.currentTimeMillis();
        long timeSeconds = TimeUnit.MILLISECONDS.toSeconds(javaTime);

        WorkflowOptions options =
                WorkflowOptions.newBuilder()
                        .setWorkflowId("WarehouseSaga-" + timeSeconds)
                        .setTaskQueue(WAREHOUSE_TASK_QUEUE)
                        .build();
        WarehouseSaga workflow = client.newWorkflowStub(WarehouseSaga.class, options);

        // start the workflow
        WorkflowClient.start(workflow::orderFulfillment);
    }

    @SuppressWarnings("CatchAndPrintStackTrace")
    public static void main(String[] args) throws Exception {
        runWorkflow();
        System.exit(0);
    }
}
