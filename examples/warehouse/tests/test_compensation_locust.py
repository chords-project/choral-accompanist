"""Real greenlet scheduling checks in the load generator image."""
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "loadgenerator"))

if importlib.util.find_spec("locust") and importlib.util.find_spec("gevent"):
    import compensation
    import gevent
else:
    compensation = None


@unittest.skipIf(compensation is None, "Locust and gevent are required")
class StockOutSchedulingTests(unittest.TestCase):
    def test_exact_count_with_delayed_http(self):
        options = SimpleNamespace(benchmark_system="accompanist", benchmark_endpoint="",
                                  stock_count=2, failure_point="stock", fulfillment_capacity=2,
                                  request_rate=10, max_concurrent_requests=10,
                                  drain_timeout=2)
        environment = SimpleNamespace(parsed_options=options, runner=SimpleNamespace(state="stopped"),
                                      events=SimpleNamespace(request=Mock()), process_exit_code=0)
        with tempfile.TemporaryDirectory() as tmp, patch.object(compensation, "ROOT", Path(tmp)), \
             patch.object(compensation, "set_stock", return_value=2) as stock, \
             patch.object(compensation, "set_fulfillment_capacity", return_value=1_000_000_000) as capacity, \
             patch.object(compensation, "get_loyalty_points", return_value=7) as loyalty:
            run = compensation.Run(environment)
            def delayed_response(*args, **kwargs):
                gevent.sleep(.2)
                return SimpleNamespace(status_code=200, content=b"ok", raise_for_status=lambda: None)
            with patch.object(compensation.requests, "get", side_effect=delayed_response):
                run.execute()
            rows = [json.loads(line) for line in (run.evidence.path / "requests.jsonl").read_text().splitlines()]
            self.assertEqual([sum(row["event"] == event for row in rows)
                              for event in ("scheduled", "dispatch", "response", "missed")], [4, 4, 4, 0])
            self.assertFalse(run.errors)
            stock.assert_called_once_with(2)
            capacity.assert_called_once_with(1_000_000_000)
            loyalty.assert_called_once_with("accompanist")


if __name__ == "__main__":
    unittest.main()
