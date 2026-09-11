"""Deterministic arrival decisions, independent of HTTP and Locust user count."""
import math

SYSTEMS = {
    "accompanist": {"endpoint": "http://warehouse:5000", "deployment": "payment", "collector": "postgres"},
    "temporal": {"endpoint": "http://temporal-warehouse-endpoint:5001", "deployment": "temporal-worker-payment", "collector": "temporal-api"},
}


def resolve(options):
    config = dict(SYSTEMS[options.benchmark_system])
    config.update(system=options.benchmark_system, rate=options.request_rate,
                  duration=options.submission_duration, fault_after=options.fault_after_seconds,
                  fault_duration=options.fault_duration_seconds, drain_timeout=options.drain_timeout)
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


def arrival(index, now, rate, active, cap):
    """An arrival more than one interval late is missed; never catch up."""
    scheduled = index / rate
    if now - scheduled >= 1 / rate:
        return "scheduler-lag"
    if active >= cap:
        return "concurrency-limit"
    return "dispatch"
