"""Prepare the warehouse business stock before a stock-out benchmark run."""
import os
import subprocess

PRODUCT_ID = 123


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
