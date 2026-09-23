"""Deterministic arrival decisions, independent of HTTP and Locust user count."""
import math
import os

SYSTEMS = {
    "accompanist": {"endpoint": "http://warehouse:5000", "deployment": "payment", "collector": "postgres"},
    "temporal": {"endpoint": "http://temporal-warehouse-endpoint:5001", "deployment": "temporal-worker-payment", "collector": "temporal-api"},
}

RUNTIME_SETTING_SUFFIXES = (
    "EXECUTION_CONCURRENCY",
    "ACTIVITY_EXECUTION_CONCURRENCY",
    "WORKFLOW_TASK_EXECUTION_CONCURRENCY",
    "DELIVERY_CONCURRENCY",
    "SCAN_BATCH_SIZE",
    "WORKER_POLLERS",
    "DB_POOL_SIZE",
)


def runtime_settings(system):
    """Return the resolved ConfigMap values recorded with every benchmark run."""
    settings = {}
    prefix = system.upper() + "_"
    for suffix in RUNTIME_SETTING_SUFFIXES:
        name = prefix + suffix
        value = os.getenv(name)
        if value is None or not value.strip():
            continue
        try:
            parsed = int(value)
        except ValueError as error:
            raise ValueError(f"{name} must be a positive integer") from error
        if parsed <= 0:
            raise ValueError(f"{name} must be a positive integer")
        settings[suffix.lower()] = parsed
    return settings


def resolve(options):
    config = dict(SYSTEMS[options.benchmark_system])
    config.update(system=options.benchmark_system, rate=options.request_rate,
                  duration=options.submission_duration, fault_after=options.fault_after_seconds,
                  fault_duration=options.fault_duration_seconds, drain_timeout=options.drain_timeout,
                  runtime_settings=runtime_settings(options.benchmark_system))
    if options.benchmark_endpoint:
        config["endpoint"] = options.benchmark_endpoint.rstrip("/")
    for key in ("rate", "duration", "drain_timeout", "fault_duration"):
        if not math.isfinite(config[key]) or config[key] <= 0:
            raise ValueError(f"{key} must be positive and finite")
    if not 0 <= config["fault_after"] < config["duration"]:
        raise ValueError("outage delay must fall inside submission duration")
    config["max_concurrent"] = options.max_concurrent_requests or math.ceil(config["rate"] * (config["duration"] + config["drain_timeout"]))
    if config["max_concurrent"] < 1:
        raise ValueError("maximum concurrency must be positive")
    return config


def resolve_compensation(options):
    """Resolve a fixed request count; the rate determines the submission window."""
    failure_point = getattr(options, "failure_point", "stock")
    if failure_point not in ("stock", "fulfillment"):
        raise ValueError("failure point must be stock or fulfillment")
    fulfillment_capacity = getattr(options, "fulfillment_capacity", 750)
    resource_count = options.stock_count if failure_point == "stock" else fulfillment_capacity
    if resource_count < 1 or resource_count > 1_000_000_000:
        raise ValueError("resource count must be between 1 and 1,000,000,000")
    if not math.isfinite(options.request_rate) or options.request_rate <= 0:
        raise ValueError("request rate must be positive and finite")
    if not math.isfinite(options.drain_timeout) or options.drain_timeout <= 0:
        raise ValueError("drain timeout must be positive and finite")
    system = options.benchmark_system
    config = dict(SYSTEMS[system])
    count = resource_count * 2
    duration = count / options.request_rate
    cap = options.max_concurrent_requests or math.ceil(options.request_rate * (duration + options.drain_timeout))
    if cap < 1:
        raise ValueError("maximum concurrency must be positive")
    config.update(benchmark="compensation", system=system, failure_point=failure_point,
                  stock_count=options.stock_count, fulfillment_capacity=fulfillment_capacity,
                  resource_count=resource_count, product_id=123, capacity_id=1,
                  request_count=count, rate=options.request_rate,
                  duration=duration, drain_timeout=options.drain_timeout, max_concurrent=cap,
                  runtime_settings=runtime_settings(system))
    if options.benchmark_endpoint:
        config["endpoint"] = options.benchmark_endpoint.rstrip("/")
    return config


def arrival(index, now, rate, active, cap):
    """An arrival more than one interval late is missed; never catch up."""
    scheduled = index / rate
    if now - scheduled >= 1 / rate:
        return "scheduler-lag"
    if active >= cap:
        return "concurrency-limit"
    return "dispatch"
