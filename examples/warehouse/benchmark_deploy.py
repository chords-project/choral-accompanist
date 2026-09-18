#!/usr/bin/env python3
"""Build and deploy one benchmark stack, retaining evidence and shared storage."""
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STACKS = {
    'accompanist': ['warehouse', 'payment', 'loyalty', 'db-payment', 'db-loyalty', 'warehouse-monitor', 'lgtm'],
    'temporal': ['temporal-worker-workflow', 'temporal-worker-warehouse', 'temporal-worker-payment',
                 'temporal-worker-loyalty', 'temporal-warehouse-endpoint', 'temporal-frontend',
                 'temporal-admin-tools', 'temporal-ui', 'postgresql'],
}

ECR_REPOSITORY = re.compile(
    r'^(?:https?://)?(?P<registry>[0-9]+\.dkr\.ecr\.(?P<region>[a-z0-9-]+)\.amazonaws\.com(?:\.cn)?)(?:/|$)'
)


def authenticate_registry(default_repo):
    """Log Docker into private ECR when the selected repository is hosted there."""
    match = ECR_REPOSITORY.match(default_repo or '')
    if not match:
        return
    password = subprocess.run(
        ['aws', 'ecr', 'get-login-password', '--region', match.group('region')],
        check=True, stdout=subprocess.PIPE,
    ).stdout
    subprocess.run(
        ['docker', 'login', '--username', 'AWS', '--password-stdin', match.group('registry')],
        input=password, check=True,
    )


def remote_build_options(default_repo):
    """Match images to the x86-64 EKS nodes without colliding with native Mac tags."""
    if not ECR_REPOSITORY.match(default_repo or ''):
        return []
    revision = subprocess.check_output(
        ['git', 'rev-parse', '--short', 'HEAD'], cwd=ROOT, text=True,
    ).strip()
    fingerprint = working_tree_fingerprint()
    if fingerprint:
        revision = f'{revision}-{fingerprint}'
    return ['--platform', 'linux/amd64', '--tag', f'{revision}-linux-amd64']


def working_tree_fingerprint():
    """Identify uncommitted build-input changes so immutable ECR tags remain unique."""
    pathspecs = [
        ':(top)accompanist',
        ':(top)examples/warehouse',
        ':(exclude,top)examples/warehouse/results',
        ':(exclude,top)examples/warehouse/aws-deployment',
    ]
    changed = subprocess.check_output(
        ['git', 'diff', '--binary', 'HEAD', '--', *pathspecs], cwd=ROOT,
    )
    untracked = subprocess.check_output(
        ['git', 'ls-files', '--others', '--exclude-standard', '--full-name', '-z', '--', *pathspecs],
        cwd=ROOT,
    ).split(b'\0')
    digest = hashlib.sha256(changed)
    has_changes = bool(changed)
    for encoded_path in sorted(path for path in untracked if path):
        path = encoded_path.decode('utf-8', errors='surrogateescape')
        if '__pycache__' in Path(path).parts or path.endswith(('.pyc', '.pyo')):
            continue
        digest.update(encoded_path)
        digest.update(b'\0')
        digest.update((ROOT.parents[1] / path).read_bytes())
        has_changes = True
    return digest.hexdigest()[:8] if has_changes else ''


def ensure_namespace(context, namespace):
    """Create or retain the dedicated benchmark namespace."""
    manifest = {
        'apiVersion': 'v1',
        'kind': 'Namespace',
        'metadata': {'name': namespace},
    }
    subprocess.run(
        ['kubectl', '--context', context, 'apply', '-f', '-'],
        input=json.dumps(manifest), text=True, check=True,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('system', choices=STACKS)
    parser.add_argument('--context', required=True)
    parser.add_argument('--namespace', default='warehouse-benchmark', help='Dedicated namespace to create or reuse')
    parser.add_argument('--default-repo', help='Registry prefix, required for EKS')
    args = parser.parse_args()
    authenticate_registry(args.default_repo)
    ensure_namespace(args.context, args.namespace)
    kube = ['kubectl', '--context', args.context, '-n', args.namespace]
    def output(*cmd):
        return subprocess.check_output([*kube, *cmd], text=True, timeout=60)
    existing = json.loads(output('get','deployment','loadgenerator','--ignore-not-found','-o','json') or '{}')
    pvc = output('get', 'pvc', 'benchmark-runs', '--ignore-not-found', '-o', 'name').strip()
    if pvc and not existing:
        raise SystemExit('Existing evidence PVC but no Locust deployment; restore the prior deployment and collect before switching')
    if existing:
        # Fail closed if the Pod cannot answer; absence of evidence is not proof of a drained run.
        guard = output('exec','deploy/loadgenerator','--','python','-c',
                       'from pathlib import Path; p=Path("/runs/active-run"); print(p.read_text() if p.exists() else "")').strip()
        if guard:
            raise SystemExit(f'Collect and drain run {guard} before deploying or switching stacks')
    profile = ['-p','temporal'] if args.system == 'temporal' else []
    registry = ['--default-repo',args.default_repo] if args.default_repo else []
    build_options = remote_build_options(args.default_repo)
    with tempfile.TemporaryDirectory(prefix='warehouse-deploy-') as directory:
        artifacts = str(Path(directory)/'images.json')
        subprocess.run(
            ['skaffold', 'build', *profile, *registry, *build_options,
             '--kube-context', args.context, '--file-output', artifacts],
            cwd=ROOT, check=True,
        )
        rendered = subprocess.check_output(
            ['skaffold', 'render', *profile, *registry, '--build-artifacts', artifacts],
            cwd=ROOT, text=True,
        )
        # Disable UI starts during switching, then recheck the persistent guard to close the race.
        if existing:
            subprocess.run([*kube,'scale','deployment/loadgenerator','--replicas=0'],check=True)
            subprocess.run([*kube,'wait','--for=delete','pod','-l','app=loadgenerator','--timeout=650s'],check=True)
            # Mount the same PVC in a short-lived read-only guard reader.
            check = {'apiVersion':'v1','kind':'Pod','metadata':{'name':'benchmark-guard-check'},'spec':{
                'restartPolicy':'Never','containers':[{'name':'check','image':'busybox:1.37.0',
                'command':['sh','-c','if [ -e /runs/active-run ]; then cat /runs/active-run; exit 1; fi'],
                'volumeMounts':[{'name':'runs','mountPath':'/runs','readOnly':True}]}],
                'volumes':[{'name':'runs','persistentVolumeClaim':{'claimName':'benchmark-runs'}}]}}
            subprocess.run([*kube,'delete','pod','benchmark-guard-check','--ignore-not-found'],check=True)
            subprocess.run([*kube,'apply','-f','-'],input=json.dumps(check),text=True,check=True)
            import time
            try:
                deadline=time.monotonic()+120
                while time.monotonic()<deadline:
                    phase=json.loads(output('get','pod','benchmark-guard-check','-o','json')).get('status',{}).get('phase')
                    if phase=='Succeeded': break
                    if phase=='Failed': raise RuntimeError('A run started during deployment; restore Locust and collect it first')
                    time.sleep(1)
                else: raise RuntimeError('Cannot verify benchmark guard')
            except Exception:
                subprocess.run([*kube,'scale','deployment/loadgenerator','--replicas=1'],check=True)
                raise
            finally:
                subprocess.run([*kube,'delete','pod','benchmark-guard-check','--ignore-not-found'],check=True)
        other = 'temporal' if args.system=='accompanist' else 'accompanist'
        subprocess.run([*kube,'delete','deployment',*STACKS[other],'--ignore-not-found','--wait=true'],check=True)
        subprocess.run([*kube,'delete','service',*STACKS[other],'--ignore-not-found'],check=True)
        subprocess.run([*kube,'apply','-f','-'],input=rendered,text=True,check=True)
        subprocess.run([*kube,'rollout','status','deployment','--timeout=600s'],check=True)
    port_forward = shlex.join([
        'kubectl', '--context', args.context, '--namespace', args.namespace,
        'port-forward', 'service/loadgenerator', '8089:8089',
    ])
    print(f'{args.system} ready. Start the load-generator port forward with:\n{port_forward}')


if __name__=='__main__': main()
