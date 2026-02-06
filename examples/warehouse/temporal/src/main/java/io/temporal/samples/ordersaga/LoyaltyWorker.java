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

import io.temporal.samples.ordersaga.web.ServerInfo;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class LoyaltyWorker {

    private static final Logger logger = LoggerFactory.getLogger(LoyaltyWorker.class);

    @SuppressWarnings("CatchAndPrintStackTrace")
    public static void main(String[] args) throws Exception {

        final String TASK_QUEUE = ServerInfo.getLoyaltyTaskQueue();

        // worker factory that can be used to create workers for specific task queues
        WorkerFactory factory = WorkerFactory.newInstance(TemporalClient.get());

        // register loyalty worker
        io.temporal.worker.Worker loyaltyWorker = factory.newWorker(TASK_QUEUE, TemporalClient.getWorkerOptions());
        var dbUrl = System.getenv().getOrDefault("POSTGRES_URL", "postgresql://localhost:5432/warehouse_loyalty");
        loyaltyWorker.registerActivitiesImplementations(new LoyaltyActivitiesImpl(ServerInfo.getDatabase(dbUrl)));

        // Start all workers created by this factory.
        factory.start();
        logger.info("Worker loyalty started for task queues: {}", TASK_QUEUE);

        while (!factory.isTerminated()) {
            factory.awaitTermination(1000, TimeUnit.DAYS);
        }
    }
}
