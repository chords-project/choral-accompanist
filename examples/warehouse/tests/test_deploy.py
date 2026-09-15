"""Deployment helper tests that do not require benchmark runtime dependencies."""
import sys
import json
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import benchmark_deploy


class RegistryAuthenticationTests(unittest.TestCase):
    @patch.object(benchmark_deploy.subprocess, 'run')
    def test_private_ecr_login_uses_repository_region(self, run):
        run.return_value.stdout = b'temporary-password\n'
        benchmark_deploy.authenticate_registry(
            '727646482479.dkr.ecr.eu-central-1.amazonaws.com/warehouse-eks-example'
        )
        self.assertEqual(run.call_args_list[0].args[0], [
            'aws', 'ecr', 'get-login-password', '--region', 'eu-central-1'
        ])
        self.assertEqual(run.call_args_list[1].args[0], [
            'docker', 'login', '--username', 'AWS', '--password-stdin',
            '727646482479.dkr.ecr.eu-central-1.amazonaws.com'
        ])
        self.assertEqual(run.call_args_list[1].kwargs['input'], b'temporary-password\n')

    @patch.object(benchmark_deploy.subprocess, 'run')
    def test_non_ecr_repository_does_not_login(self, run):
        benchmark_deploy.authenticate_registry('localhost:5000/warehouse')
        run.assert_not_called()

    @patch.object(benchmark_deploy.subprocess, 'run')
    def test_namespace_is_applied_idempotently(self, run):
        benchmark_deploy.ensure_namespace('eks-context', 'warehouse-benchmark')
        self.assertEqual(run.call_args.args[0], [
            'kubectl', '--context', 'eks-context', 'apply', '-f', '-'
        ])
        manifest = json.loads(run.call_args.kwargs['input'])
        self.assertEqual(manifest, {
            'apiVersion': 'v1',
            'kind': 'Namespace',
            'metadata': {'name': 'warehouse-benchmark'},
        })

    @patch.object(benchmark_deploy.subprocess, 'check_output', return_value='455e2f4\n')
    def test_ecr_build_targets_x86_with_distinct_tag(self, check_output):
        options = benchmark_deploy.remote_build_options(
            '727646482479.dkr.ecr.eu-central-1.amazonaws.com/warehouse-eks-example'
        )
        self.assertEqual(options, [
            '--platform', 'linux/amd64', '--tag', '455e2f4-linux-amd64'
        ])

    @patch.object(benchmark_deploy.subprocess, 'check_output')
    def test_local_build_uses_native_platform_and_tag(self, check_output):
        self.assertEqual(benchmark_deploy.remote_build_options(None), [])
        check_output.assert_not_called()


if __name__ == '__main__':
    unittest.main()
