import asyncio
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'results'))
from collect_run import collect_temporal, collect_accompanist


class CollectorTests(unittest.TestCase):
    def test_temporal_collects_all_history_pages(self):
        from temporalio.api.workflowservice.v1 import DescribeWorkflowExecutionResponse, GetWorkflowExecutionHistoryResponse
        from temporalio.api.workflow.v1 import WorkflowExecutionInfo
        from temporalio.api.common.v1 import WorkflowExecution
        from google.protobuf.timestamp_pb2 import Timestamp
        info = WorkflowExecutionInfo(execution=WorkflowExecution(workflow_id='benchmark-id',run_id='server-run'),
                                     status=2,start_time=Timestamp(seconds=10),close_time=Timestamp(seconds=90))
        service=SimpleNamespace(describe_workflow_execution=AsyncMock(return_value=DescribeWorkflowExecutionResponse(workflow_execution_info=info)),
                                get_workflow_execution_history=AsyncMock(side_effect=[GetWorkflowExecutionHistoryResponse(next_page_token=b'next'),GetWorkflowExecutionHistoryResponse()]))
        with tempfile.TemporaryDirectory() as directory:
            bundle=Path(directory);(bundle/'raw').mkdir()
            with patch('temporalio.client.Client.connect',new=AsyncMock(return_value=SimpleNamespace(workflow_service=service))):
                records=asyncio.run(collect_temporal('test','default',[{'execution_id':'benchmark-id','request_id':'r'}],bundle))
            self.assertEqual(records[0]['terminal_at'],90)
            self.assertEqual(records[0]['temporal_run_id'],'server-run')
            self.assertEqual(len(json.loads((bundle/'raw/benchmark-id.json').read_text())['history_pages']),2)
            self.assertEqual(service.get_workflow_execution_history.call_args_list[1].args[0].next_page_token,b'next')
            self.assertIn('timeout', service.describe_workflow_execution.call_args.kwargs)
            self.assertIn('timeout', service.get_workflow_execution_history.call_args.kwargs)
    def test_postgres_includes_failed_running_and_unstarted_correlations(self):
        raw='session_id,run_id,session_state,started_at,completed_at,failed_at,attempt_count,restart_count,request_id,accepted_at\n'
        raw+='1,run,failed,2026-01-01T00:00:00+00:00,,2026-01-01T00:01:00+00:00,2,1,r,2026-01-01T00:00:00+00:00\n'
        raw+=',run,,,,,,,pending,2026-01-01T00:00:00+00:00\n'
        with tempfile.TemporaryDirectory() as directory:
            bundle=Path(directory);(bundle/'raw').mkdir()
            records=collect_accompanist(SimpleNamespace(run=lambda *args:raw),'run',bundle)
        self.assertEqual([r['status'] for r in records],['failed','running'])
        self.assertIsNone(records[1]['started_at'])

    def test_arrivals_before_outage_use_floor_not_truncation(self):
        from plot_run import bucket_counts
        self.assertEqual(bucket_counts([99.01, 100.01, 101.01], 100), {-1: 1, 0: 1, 1: 1})

    def test_mailbox_plot_excludes_session_state_rows(self):
        from plot_run import mailbox_series
        samples = io.StringIO(
            "observed_at_utc,service,run_id,pending_outbox,total_outbox,inbox_total,state,session_count\n"
            "2026-01-01T00:00:01Z,warehouse,run,2,3,1,,0\n"
            "2026-01-01T00:00:01Z,warehouse,run,,,,started,2\n"
            "2026-01-01T00:00:02Z,payment,run,4,5,2,,0\n")
        xs, ys = mailbox_series(samples, "run", 1767225600)
        self.assertEqual(xs, [1])
        self.assertEqual(ys, [2])
