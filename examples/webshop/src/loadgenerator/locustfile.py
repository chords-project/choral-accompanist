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

    min_products = max(0, environment.parsed_options.min_product_count)
    max_products = min(len(products), environment.parsed_options.max_product_count)
    print(f"min products: {min_products}, max products: {max_products}")

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

@events.init_command_line_parser.add_listener
def _(parser):
    parser.add_argument("--min-product-count", default=1, help="The minimum number of items in the cart duing checkout")
    parser.add_argument("--max-product-count", default=9, help="The maximum number of items in the cart duing checkout")

products = [
    '0PUK6V6EV0',
    '1YMWWN1N4O',
    '2ZYFJ3GM2N',
    '66VCHSJNUP',
    '6E92ZMYYFZ',
    '9SIQT8TOJO',
    'L9ECAV7KIM',
    'LS4PSXUNUM',
    'OLJCESPC7Z']


def index(l):
    l.client.get("/")


def setCurrency(l):
    currencies = ['EUR', 'USD', 'JPY', 'CAD', 'GBP', 'TRY']
    l.client.post("/setCurrency",
                  {'currency_code': random.choice(currencies)})


def browseProduct(l):
    l.client.get("/product/" + random.choice(products))


def viewCart(l):
    l.client.get("/cart")


def addToCart(l):
    product = random.choice(products)
    l.client.get("/product/" + product)
    l.client.post("/cart", {
        'product_id': product,
        'quantity': random.randint(1, 10)})


def empty_cart(l):
    l.client.post('/cart/empty')


def checkout(l):
    addToCart(l)
    current_year = datetime.datetime.now().year+1
    l.client.post("/cart/checkout/choreography", {
        'email': fake.email(),
        'street_address': fake.street_address(),
        'zip_code': fake.zipcode(),
        'city': fake.city(),
        'state': fake.state_abbr(),
        'country': fake.country(),
        'credit_card_number': fake.credit_card_number(card_type="visa"),
        'credit_card_expiration_month': random.randint(1, 12),
        'credit_card_expiration_year': random.randint(current_year, current_year + 70),
        'credit_card_cvv': f"{random.randint(100, 999)}",
    })


def logout(l):
    l.client.get('/logout')

def checkout_flow(l, checkout_endpoint):
        #setCurrency(l)

        # Add products to cart
        #max_products = random.randrange(1, 4)

        min_products = max(0, l.user.environment.parsed_options.min_product_count)
        max_products = min(len(products), l.user.environment.parsed_options.max_product_count)
        products_count = random.randrange(min_products, max_products+1)

        buy_products = random.sample(products, products_count)
        for product in buy_products:
            l.client.post("/cart", {
                'product_id': product,
                'quantity': random.randint(1, 10)})

        # Checkout
        current_year = datetime.datetime.now().year+1
        l.client.post(checkout_endpoint, {
            'email': fake.email(),
            'street_address': fake.street_address(),
            'zip_code': fake.zipcode(),
            'city': fake.city(),
            'state': fake.state_abbr(),
            'country': fake.country(),
            'credit_card_number': fake.credit_card_number(card_type="visa"),
            'credit_card_expiration_month': random.randint(1, 12),
            'credit_card_expiration_year': random.randint(current_year, current_year + 70),
            'credit_card_cvv': f"{random.randint(100, 999)}",
        })

class UserBehavior(TaskSet):

    def on_start(self):
        index(self)
        setCurrency(self)

    tasks = {
        index: 1,
        setCurrency: 2,
        browseProduct: 10,
        addToCart: 2,
        viewCart: 3,
        checkout: 1
    }


class OrchestratedCheckout(TaskSet):

    def on_start(self):
        index(self)
        self.client.post("/setCurrency", {'currency_code': 'EUR'})

    @task
    def checkout(self):
        checkout_flow(self, "/cart/checkout/orchestrator")

class ChoreographicCheckout(TaskSet):

    def on_start(self):
        index(self)
        self.client.post("/setCurrency", {'currency_code': 'EUR'})

    @task
    def checkout(self):
        checkout_flow(self, "/cart/checkout/choreography")

class OrchestratedCheckoutUser(FastHttpUser):
    tasks = [OrchestratedCheckout]
    # wait_time = constant_pacing(5)
    wait_time = between(1, 5)

class ChoreographicCheckoutUser(FastHttpUser):
    tasks = [ChoreographicCheckout]
    # wait_time = constant_pacing(5)
    wait_time = between(1, 5)

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
