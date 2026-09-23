"""Prepare warehouse resources before a compensation benchmark run."""
import os
import subprocess

PRODUCT_ID = 123
CAPACITY_ID = 1
USER_ID = 100
DEFAULT_RESOURCE_COUNT = 1_000_000_000


def stock_sql(sql):
    env = dict(os.environ, PGHOST="db-warehouse", PGPORT="5432", PGDATABASE="warehouse",
               PGUSER="postgres", PGPASSWORD="postgres")
    result = subprocess.run(["psql", "-X", "-v", "ON_ERROR_STOP=1", "-At", "-c", sql],
                            env=env, capture_output=True, text=True, timeout=30, check=True)
    return result.stdout.strip()


def set_stock(count):
    # The run guard is allocated before this call. No order may be in flight from a prior run.
    sql = ("CREATE TABLE IF NOT EXISTS products (product_id INT PRIMARY KEY, "
           "stock_quantity INT NOT NULL DEFAULT 0); "
           f"INSERT INTO products (product_id, stock_quantity) VALUES ({PRODUCT_ID}, {count}) "
           "ON CONFLICT (product_id) DO UPDATE SET stock_quantity = EXCLUDED.stock_quantity; "
           f"SELECT stock_quantity FROM products WHERE product_id = {PRODUCT_ID};")
    observed = int(stock_sql(sql).splitlines()[-1])
    if observed != count:
        raise RuntimeError(f"stock setup mismatch: expected {count}, observed {observed}")
    return observed


def set_fulfillment_capacity(count):
    # The run guard is allocated before this call. No order may be in flight from a prior run.
    sql = ("CREATE TABLE IF NOT EXISTS fulfillment_capacity (capacity_id INT PRIMARY KEY, "
           "remaining_capacity INT NOT NULL CHECK (remaining_capacity >= 0)); "
           f"INSERT INTO fulfillment_capacity (capacity_id, remaining_capacity) VALUES ({CAPACITY_ID}, {count}) "
           "ON CONFLICT (capacity_id) DO UPDATE SET remaining_capacity = EXCLUDED.remaining_capacity; "
           f"SELECT remaining_capacity FROM fulfillment_capacity WHERE capacity_id = {CAPACITY_ID};")
    observed = int(stock_sql(sql).splitlines()[-1])
    if observed != count:
        raise RuntimeError(f"fulfillment capacity setup mismatch: expected {count}, observed {observed}")
    return observed


def get_loyalty_points(system):
    host, database = ("db-loyalty", "loyalty") if system == "accompanist" else ("db-warehouse", "warehouse")
    env = dict(os.environ, PGHOST=host, PGPORT="5432", PGDATABASE=database,
               PGUSER="postgres", PGPASSWORD="postgres")
    sql = ("CREATE TABLE IF NOT EXISTS loyalty_points (user_id INT PRIMARY KEY, points INT NOT NULL DEFAULT 0); "
           f"INSERT INTO loyalty_points (user_id, points) VALUES ({USER_ID}, 0) ON CONFLICT DO NOTHING; "
           f"SELECT points FROM loyalty_points WHERE user_id = {USER_ID};")
    result = subprocess.run(["psql", "-X", "-v", "ON_ERROR_STOP=1", "-At", "-c", sql],
                            env=env, capture_output=True, text=True, timeout=30, check=True)
    return int(result.stdout.strip().splitlines()[-1])
