"""No cluster required: real greenlet scheduling with delayed mock HTTP responses."""
import sys
from pathlib import Path
sys.path[:0] = [str(Path(__file__).resolve().parents[1] / name) for name in ("loadgenerator", "results")]
# Locust monkey-patches networking; import it before the other test dependencies.
import fault_tolerance as ft
import json
import tempfile
import unittest
import os
from types import SimpleNamespace
from unittest.mock import Mock, patch
import gevent
from benchmark_core import arrival, resolve
from export_run_bundle import export, read_jsonl


def options(**updates):
    values = dict(benchmark_system="temporal", benchmark_endpoint="", request_rate=10,
                  submission_duration=1, fault_after_seconds=.05, fault_duration_seconds=.06,
                  max_concurrent_requests=100, drain_timeout=1)
    values.update(updates)
    return SimpleNamespace(**values)


class FakeFault:
    def __init__(self, run):
        self.run = run
        self.changed = False
    def prepare(self):
        self.run.config['original_replicas'] = 3
    def inject(self):
        self.run.stop.wait(timeout=self.run.config['fault_after'])
        if self.run.stop.is_set(): return
        self.changed = True
        self.run.event('target-unavailable')
        self.run.stop.wait(timeout=self.run.config['fault_duration'])
        self.restore()
    def restore(self):
        if self.changed:
            self.changed = False
            self.run.event('target-ready')


class SchedulingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.patchers = [patch.object(ft, 'ROOT', self.root), patch.object(ft, 'FaultController', FakeFault)]
        for p in self.patchers: p.start()
    def tearDown(self):
        for p in reversed(self.patchers): p.stop()
        self.temp.cleanup()
    def run_benchmark(self, delay=.4, **updates):
        env = SimpleNamespace(parsed_options=options(**updates), runner=SimpleNamespace(state='stopped'),
                              events=SimpleNamespace(request=Mock()), process_exit_code=0)
        run = ft.Run(env)
        def response(*args, **kwargs):
            gevent.sleep(delay)
            return SimpleNamespace(status_code=200, content=b'ok', raise_for_status=lambda: None)
        with patch.object(ft.requests, 'get', side_effect=response):
            run.execute()
        return run, read_jsonl(run.evidence.path / 'requests.jsonl')
    def test_delayed_responses_do_not_reduce_arrival_rate(self):
        run, rows = self.run_benchmark()
        dispatches = [r for r in rows if r['event']=='dispatch']
        self.assertEqual(len(dispatches), 10)
        self.assertLess(dispatches[-1]['timestamp']-dispatches[0]['timestamp'], 1.2)
        self.assertEqual(len([r for r in rows if r['event']=='response']), 10)
        self.assertFalse(run.errors)
    def test_concurrency_exhaustion_records_misses_without_retry(self):
        run, rows = self.run_benchmark(max_concurrent_requests=1)
        self.assertTrue(any(r.get('reason')=='concurrency-limit' for r in rows))
        self.assertEqual(len([r for r in rows if r['event']=='scheduled']),10)
        self.assertTrue(run.errors)
    def test_lag_skips_old_arrivals(self):
        self.assertEqual(arrival(0, 1, 5, 0, 100), 'scheduler-lag')
        self.assertEqual(arrival(4, 1.01, 5, 0, 100), 'scheduler-lag')
        self.assertEqual(arrival(5, 1.01, 5, 0, 100), 'dispatch')
    def test_repeated_runs_need_collection_and_have_fresh_ids(self):
        run, _ = self.run_benchmark()
        with self.assertRaises(FileExistsError): self.run_benchmark()
        (self.root/'active-run').unlink()  # Collector's successful reconciliation.
        next_run, _ = self.run_benchmark()
        self.assertNotEqual(run.run_id, next_run.run_id)
    def test_drain_interruption_is_an_http_error(self):
        run, rows = self.run_benchmark(delay=2, drain_timeout=.03)
        self.assertTrue(any(r.get('error') for r in rows if r['event']=='response'))
        self.assertTrue(run.done.is_set())
    def test_early_stop_restores(self):
        env = SimpleNamespace(parsed_options=options(), runner=SimpleNamespace(state='stopped'),
                              events=SimpleNamespace(request=Mock()))
        run = ft.Run(env)
        with patch.object(ft.requests, 'get', side_effect=RuntimeError('timeout')):
            job=gevent.spawn(run.execute)
            gevent.sleep(.08)
            run.stop.set()
            job.join()
        self.assertFalse(run.fault.changed)
        self.assertIn('Submissions stopped early', run.errors)
    def test_default_concurrency(self):
        self.assertEqual(resolve(options(request_rate=5, submission_duration=300, drain_timeout=300,
                                         max_concurrent_requests=0))['max_concurrent'],3000)

    def test_runtime_settings_are_captured_from_environment(self):
        values = {
            'ACCOMPANIST_EXECUTION_CONCURRENCY': '128', 'ACCOMPANIST_DELIVERY_CONCURRENCY': '128',
            'ACCOMPANIST_SCAN_BATCH_SIZE': '1024', 'ACCOMPANIST_DB_POOL_SIZE': '32'
        }
        with patch.dict(os.environ, values, clear=False):
            settings = resolve(options(benchmark_system='accompanist'))['runtime_settings']
        self.assertEqual(settings, {
            'execution_concurrency': 128, 'delivery_concurrency': 128,
            'scan_batch_size': 1024, 'db_pool_size': 32
        })

    def test_temporal_runtime_settings_are_captured_independently(self):
        values = {
            'TEMPORAL_ACTIVITY_EXECUTION_CONCURRENCY': '128',
            'TEMPORAL_WORKFLOW_TASK_EXECUTION_CONCURRENCY': '96', 'TEMPORAL_WORKER_POLLERS': '16',
            'TEMPORAL_DB_POOL_SIZE': '32', 'ACCOMPANIST_EXECUTION_CONCURRENCY': '64'
        }
        with patch.dict(os.environ, values, clear=False):
            settings = resolve(options(benchmark_system='temporal'))['runtime_settings']
        self.assertEqual(settings, {
            'activity_execution_concurrency': 128, 'workflow_task_execution_concurrency': 96,
            'worker_pollers': 16, 'db_pool_size': 32
        })

    def test_runtime_settings_reject_non_positive_values(self):
        with patch.dict(os.environ, {'ACCOMPANIST_EXECUTION_CONCURRENCY': '0'}, clear=False):
            with self.assertRaisesRegex(ValueError, 'ACCOMPANIST_EXECUTION_CONCURRENCY'):
                resolve(options(benchmark_system='accompanist'))

        with patch.dict(os.environ, {'TEMPORAL_ACTIVITY_EXECUTION_CONCURRENCY': '0'}, clear=False):
            with self.assertRaisesRegex(ValueError, 'TEMPORAL_ACTIVITY_EXECUTION_CONCURRENCY'):
                resolve(options(benchmark_system='temporal'))


class FaultTests(unittest.TestCase):
    def fake(self):
        run=SimpleNamespace(config={}, evidence=Mock(), event=Mock(), error=Mock())
        return run, ft.FaultController(run)
    def test_preserves_nondefault_replica_count(self):
        run, fault=self.fake()
        with patch.object(fault,'api',return_value={'spec':{'replicas':4}}), patch.object(fault,'observe'):
            fault.prepare()
        fault.changed=True
        with patch.object(fault,'scale') as scale, patch.object(fault,'observe'):
            fault.restore()
        scale.assert_called_once_with(4)
        self.assertFalse(fault.changed)
    def test_api_failure_retries_restoration_and_marks_invalid(self):
        run,fault=self.fake()
        fault.original=3
        fault.changed=True
        with patch.object(fault,'scale',side_effect=[OSError('API unavailable'),None]) as scale, patch.object(fault,'observe'), patch.object(ft.gevent,'sleep'):
            fault.restore()
        self.assertEqual(scale.call_count,2)
        self.assertFalse(fault.changed)
        run.error.assert_called_once()
    def test_observation_timeout_raises(self):
        run,fault=self.fake()
        with patch.object(ft.time,'monotonic',side_effect=[0,121]):
            with self.assertRaises(TimeoutError): fault.observe(3,'target-ready')


class BundleTests(unittest.TestCase):
    def fixture(self, path):
        for name,value in {'run-config.json':{'run_id':'test','system':'temporal','rate':1,'duration':1},
                           'run-status.json':{'stopped':True,'restored':True},
                           'executions.json':[{'request_id':'r','status':'completed','started_at':10,'terminal_at':25}]}.items():
            (path/name).write_text(json.dumps(value))
        (path/'requests.jsonl').write_text('\n'.join(json.dumps({'event':e,'request_id':'r'}) for e in ['scheduled','dispatch','response']))
        (path/'fault-events.jsonl').write_text('\n'.join(json.dumps({'phase':p,'timestamp':t}) for p,t in [('target-unavailable',12),('target-ready',20)]))
        (path/'validation-errors.jsonl').touch()
    def test_http_timeout_can_finish_durably_and_recovery_uses_durable_time(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder);self.fixture(p)
            manifest=export(p)
            self.assertTrue(manifest['valid'])
            self.assertEqual(manifest['outage_cohort_recovery']['seconds'],5)
    def test_unknown_requests_are_never_successful(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder);self.fixture(p)
            (p/'executions.json').write_text('[]')
            manifest=export(p)
            self.assertFalse(manifest['valid'])
            self.assertFalse(manifest['complete'])
            self.assertEqual(manifest['outage_cohort_recovery']['status'],'unresolved')
    def test_failed_and_incomplete_cohorts(self):
        for state in ['failed','running']:
            with tempfile.TemporaryDirectory() as folder:
                p=Path(folder);self.fixture(p)
                (p/'executions.json').write_text(json.dumps([{'request_id':'r','status':state,'started_at':10,'terminal_at':25 if state=='failed' else None}]))
                manifest=export(p)
                self.assertIsNone(manifest['outage_cohort_recovery']['seconds'])
                self.assertEqual(manifest['complete'],state=='failed')
    def test_missing_artifacts_export_invalid_bundle(self):
        with tempfile.TemporaryDirectory() as folder:
            manifest=export(Path(folder))
            self.assertFalse(manifest['valid'])
            self.assertFalse(manifest['complete'])


if __name__=='__main__': unittest.main()
