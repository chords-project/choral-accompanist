"""Stock-out benchmark contracts without requiring a cluster or Locust."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "loadgenerator"), str(ROOT / "results")]

from benchmark_core import arrival, resolve_compensation
from collect_run import releasable
from export_run_bundle import export
import stock_db


class StockOutTests(unittest.TestCase):
    def options(self, **updates):
        values = dict(benchmark_system="temporal", benchmark_endpoint="", stock_count=750,
                      request_rate=5, max_concurrent_requests=0, drain_timeout=300)
        values.update(updates)
        return SimpleNamespace(**values)

    def test_default_count_and_exact_arrival_window(self):
        config = resolve_compensation(self.options())
        self.assertEqual(config["request_count"], 1500)
        self.assertEqual(config["duration"], 300)
        self.assertEqual(config["max_concurrent"], 3000)
        self.assertEqual(sum(i / config["rate"] < config["duration"] for i in range(config["request_count"])), 1500)
        self.assertEqual(arrival(2, .61, 5, 0, 100), "scheduler-lag")

    def test_stock_setup_verifies_upserted_value(self):
        with patch.object(stock_db, "stock_sql", return_value="750") as query:
            self.assertEqual(stock_db.set_stock(750), 750)
        self.assertIn("ON CONFLICT (product_id) DO UPDATE", query.call_args.args[0])
        self.assertIn("VALUES (123, 750)", query.call_args.args[0])
        with patch.object(stock_db, "stock_sql", return_value="749"):
            with self.assertRaisesRegex(RuntimeError, "stock setup mismatch"):
                stock_db.set_stock(750)

    def fixture(self, folder, outcomes=("completed", "failed"), final_stock=0):
        config = dict(schema_version=2, benchmark="compensation", run_id="r", system="temporal",
                      stock_count=1, initial_stock=1, product_id=123, request_count=2,
                      rate=2, duration=1)
        (folder / "run-config.json").write_text(json.dumps(config))
        (folder / "run-status.json").write_text(json.dumps({"stopped": True, "restored": True}))
        (folder / "stock-final.json").write_text(json.dumps({"product_id": 123, "stock_quantity": final_stock}))
        (folder / "fault-events.jsonl").touch()
        (folder / "validation-errors.jsonl").touch()
        (folder / "requests.jsonl").write_text("\n".join(json.dumps({"event": event, "request_id": rid,
                                                                     "timestamp": 10, "http_latency_ms": 1})
                                                      for rid in ("a", "b") for event in ("scheduled", "dispatch", "response")))
        (folder / "executions.json").write_text(json.dumps([
            {"request_id": rid, "status": status, "started_at": 10, "terminal_at": 11}
            for rid, status in zip(("a", "b"), outcomes)]))

    def test_expected_failures_are_valid_and_guard_releases(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            self.fixture(folder)
            manifest = export(folder)
            self.assertTrue(manifest["valid"])
            self.assertTrue(releasable(manifest, []))
            self.assertEqual((manifest["successes"], manifest["failures"]), (1, 1))

    def test_wrong_outcome_or_stock_invalidates_but_terminal_guard_can_release(self):
        for outcomes, final_stock in ((('completed', 'completed'), 0), (('completed', 'failed'), 1)):
            with self.subTest(outcomes=outcomes, final_stock=final_stock), tempfile.TemporaryDirectory() as tmp:
                folder = Path(tmp)
                self.fixture(folder, outcomes, final_stock)
                manifest = export(folder)
                self.assertFalse(manifest["valid"])
                self.assertTrue(releasable(manifest, []))

    def test_unfinished_execution_keeps_guard(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            self.fixture(folder)
            records = json.loads((folder / "executions.json").read_text())
            records[1].update(status="running", terminal_at=None)
            (folder / "executions.json").write_text(json.dumps(records))
            manifest = export(folder)
            self.assertFalse(releasable(manifest, []))


if __name__ == "__main__":
    unittest.main()
