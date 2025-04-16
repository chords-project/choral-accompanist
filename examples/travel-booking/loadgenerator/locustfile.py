#!/usr/bin/python
#
# Copyright 2018 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import random
from locust import FastHttpUser, LoadTestShape, TaskSet, between, constant_pacing, task, events
from collections import deque
import os
from faker import Faker
import datetime

fake = Faker()

latest_requests_buf: deque[str] = None


@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    global latest_requests_buf
    print("Test starting, initializing latest_requests_buf")

    latest_requests_buf = deque(maxlen=1000)


@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    global latest_requests_buf
    if latest_requests_buf is None:
        print("Test stopped, latest_requests_buf was None, ignoring...")
        return

    print("Test stopped, saving latest_requests_buf to file /tmp/latest_requests.csv")
    with open('/tmp/latest_requests.csv', 'w') as f:
        f.write("start_time,request_type,name,response_time,response_length,url\n")
        f.writelines(latest_requests_buf)
    latest_requests_buf = None


@events.request.add_listener
def on_request_finished(request_type, name, response_time, response_length, response,
                        context, exception, start_time, url, **kwargs):
    global latest_requests_buf
    if exception is not None:
        return

    # filter out requests that are not to a checkout endpoint
    if "checkout" not in name:
        return

    latest_requests_buf.append(
        "{},{},{},{},{},{}\n".format(start_time, request_type, name, response_time, response_length, url)
    )

TRAVEL_PARAMS = {
    "fromLat": "0",
    "fromLon": "0",
    "toLat": "37.7749",
    "toLon": "-122.4194",
    "startDate": "2025-03-10",
    "endDate": "2025-03-20",
}

class TravelChoreographyUser(FastHttpUser):
    wait_time = between(1, 5)

    @task
    def choreography(self):
        self.client.get("/bookTravel/choreography", params=TRAVEL_PARAMS)


class TravelOrchestratorUser(FastHttpUser):
    wait_time = between(1, 5)

    @task
    def orchestrator(self):
        self.client.get("/bookTravel/orchestrator", params=TRAVEL_PARAMS)


class WarmupBenchmarkShape(LoadTestShape):
    stages = [
        {"duration": 2 * 60, "users": 40, "spawn_rate": 1},
        {"duration": 4 * 60, "users": 20, "spawn_rate": 1},
    ]

    def tick(self):
        run_time = self.get_run_time()

        total_duration = 0

        for stage in self.stages:
            total_duration += stage["duration"]
            if run_time < total_duration:
                return (stage["users"], stage["spawn_rate"])

        return None
