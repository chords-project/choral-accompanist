# Warehouse recovery benchmark

Use one dedicated Kubernetes namespace and one active system at a time. The benchmark
uses synchronous HTTP, a single Locust process and a fixed aggregate arrival rate.
Locust's user count does not change the rate. The ordinary `locustfile.py` remains
available separately; do not combine it with `fault_tolerance.py` or its load shape.

## Deploy and start

Prerequisites: Java 25 (and the project's Java 23 toolchain), Docker, Skaffold,
kubectl, Python 3.12+, and a running local Kubernetes cluster or EKS. These are
Kubernetes workflows; Compose automation is not provided.

From `examples/warehouse`, create a dedicated namespace and deploy:

```sh
kubectl --context docker-desktop create namespace warehouse-benchmark
python3 benchmark_deploy.py accompanist --context docker-desktop
kubectl --context docker-desktop -n warehouse-benchmark port-forward svc/loadgenerator 8089:8089
```

Open <http://localhost:8089>. The system selector shows the mapping in its help:
Accompanist uses `http://warehouse:5000`, faults `deployment/payment`, and collects
PostgreSQL sessions; Temporal uses `http://temporal-warehouse-endpoint:5001`, faults
`deployment/temporal-worker-payment`, and collects the Temporal API. The deployed
profile restricts the selector to that active system at run start. An endpoint override
changes only the HTTP address. Check the fault target before starting.

Defaults: 5 requests/s, 300 seconds of submissions, outage after 120 seconds,
60 seconds unavailable, 300 seconds to drain. Concurrency 0 derives a cap of
rate × (submission duration + drain), or 3,000 with the defaults. A missed arrival
invalidates a run rather than generating a catch-up burst. Submissions are never
retried. HTTP errors and durable execution results are distinct measurements.

Every start allocates a new UUID. Find it in the Locust Pod logs or:

```sh
kubectl --context docker-desktop -n warehouse-benchmark exec deploy/loadgenerator -- cat /runs/active-run
```

The `/runs` PVC stores fsynced request events (including correlation IDs before HTTP
submission), configuration, fault observations and status. Do not remove this PVC or
its guard to bypass an unfinished run. The guard is released only by successful
collection with no unknown or unfinished requests and verified payment restoration.
A failed terminal execution remains a reported failure but does not block another run.
Only this load generator should submit benchmark work in this dedicated namespace.

Stopping in the UI ends submissions, restores the saved replica count, and drains
outstanding HTTP requests. The controller observes Kubernetes Deployment generation,
replica, ready and available counts; outage markers are those observations, sampled
at 0.5-second intervals. It retries restoration after transient API errors and marks
such runs invalid. A forcibly killed Pod or node cannot execute cleanup: inspect
`fault-deployment.json`, restore the recorded original count manually, and keep the
run invalid. Graceful Pod termination allows up to 600 seconds for cleanup.

## Collect and plot

Install local collection dependencies in a virtual environment:

```sh
python3 -m venv ./results/.venv
. ./results/.venv/bin/activate
pip install -r results/requirements.txt
./results/collect_run.sh RUN_UUID --context docker-desktop --namespace warehouse-benchmark --output ./results
python results/plot_run.py results/RUN_UUID
```

Stop Locust before collecting. Collection determines the system from its saved
configuration. It checks durable outcomes until the original run's drain deadline,
not a new allowance on each collection. Late collection still fetches current terminal
outcomes. Unknown acceptance, unfinished executions, missing evidence and API errors
produce an invalid bundle, retained for inspection. Rerun collection after an incomplete
drain to reconcile subsequently completed work. Temporal collection opens a temporary
port-forward to `127.0.0.1:17233`; use `--temporal-address` or `--no-port-forward` for
an existing tunnel. Execution IDs avoid visibility indexing; histories are paginated.

Accompanist commits `benchmark_requests` correlation before invoking a session. It
uses a negative PostgreSQL sequence to avoid collisions with ordinary positive session
IDs. All sessions for the run are exported, including failures, pending retries and
correlations where execution has not yet started. Temporal IDs contain both UUIDs;
`temporal_run_id` is resolved through DescribeWorkflowExecution and retained alongside
raw paginated histories, including workflows whose HTTP caller timed out.

The version 1 bundle contains:

- `run-config.json`, `run-status.json`, `fault-events.jsonl`, `requests.jsonl`;
- `executions.json` with request/execution IDs, durable start and terminal Unix timestamps,
  and `running`, `completed` or `failed` status;
- `run-manifest.json` with validation errors, unknown requests and recovery status;
- `raw/` with database rows or Temporal descriptions/histories and Kubernetes versions,
  deployments, image digests, resources, replicas and nodes;
- optional Accompanist `mailbox_samples.csv` as a diagnostic.

Recovery is measured from observed payment readiness until every execution unfinished
at that instant finishes successfully. A failed or unresolved cohort has no recovery
time. Unfinished orders equal durable starts minus **all** terminals. HTTP latency is
plotted separately; durable latency includes retries. Failure throughput is separate
from successful throughput.

After collecting, switch stacks with the guarded deployment command:

```sh
python3 benchmark_deploy.py temporal --context docker-desktop
kubectl --context docker-desktop -n warehouse-benchmark port-forward svc/loadgenerator 8089:8089
kubectl --context docker-desktop -n warehouse-benchmark port-forward svc/temporal-ui 8080:8080
# Run and collect Temporal, then compare offline:
./results/collect_run.sh RUN_UUID --context docker-desktop --namespace warehouse-benchmark --output ./results
python results/plot_run.py results/ACCOMPANIST_UUID results/TEMPORAL_UUID --output results/comparison.png
```

The switching command refuses an outstanding run, builds images, stops Locust and
rechecks its persistent guard before removing the old stack. Shared evidence and
warehouse storage remain. Plots align each run to observed unavailability, shade its
own outage interval and flag incompatible workload settings or invalid evidence in
the figure and adjacent JSON report. All plots regenerate without cluster access.

## Temporal retry policy and pinned infrastructure

All Temporal orders use a 30-second Start-to-Close timeout, unlimited retries
with 1–10-second exponential backoff and no Schedule-to-Close deadline. Known business
failures are non-retryable and trigger saga compensation. Benchmark headers only
identify benchmark executions; they do not select retry behavior. The workflow input
contains only the session ID.

Temporal payment has no database dependency. Temporal loyalty uses the shared
warehouse business database and creates its own table; it does not require the
Accompanist loyalty service or database. Infrastructure and business databases remain
available during the payment fault. Activity side effects retain the example's
existing semantics; this benchmark does not add exactly-once side effects for a
process killed during a committed database operation.

Manifest pins: Temporal server `1.28.0`, UI `2.38.3`, admin-tools
`1.28.0-tctl-1.18.2-cli-1.3.0`, PostgreSQL `17.6`, BusyBox `1.37.0`, Locust `2.31.6`.
Skaffold builds application images and deployment collection records their actual
image IDs. The benchmark uses manifests, not the legacy optional Helm deployment.
These pins are reproducible inputs, not a claim that Kubernetes/EKS acceptance runs
have already passed.

## EKS

The existing Terraform foundation now defaults to Kubernetes `1.35` and AL2023 and
creates immutable ECR repositories for the six application images. Check regional
availability before provisioning; see
the [AWS version lifecycle](https://docs.aws.amazon.com/eks/latest/userguide/kubernetes-versions.html).
Provision with the existing `aws-deployment` instructions. Authenticate Docker to ECR,
then use the `skaffold_default_repo` Terraform output:

```sh
python3 benchmark_deploy.py temporal --context YOUR_EKS_CONTEXT --default-repo ECR_PREFIX
./results/collect_run.sh RUN_UUID --context YOUR_EKS_CONTEXT --namespace warehouse-benchmark --output ./results
```

Create the namespace first. EKS nodes need registry pull permissions; the existing
managed-node role supplies them. Use port forwards for Locust, Temporal UI and
Accompanist Grafana (`svc/lgtm 3000:3000`), without public dashboard ingress.

## Validation

```sh
pip install locust==2.31.6
python -m unittest discover -s tests -v
./gradlew :temporal:test :warehouse:compileJava
# SQL integration test uses a fresh schema in the specified PostgreSQL database:
ACCOMPANIST_TEST_POSTGRES_URL='jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres' \
  ./gradlew :accompanist:test --tests '*SQLRecoveryTest'
kubectl kustomize . >/tmp/accompanist.yaml
kubectl kustomize temporal >/tmp/temporal.yaml
```

For acceptance, run the default benchmark for both profiles locally, then on EKS.
Verify no missed arrivals, restoration of non-default replica counts, complete durable
drains and offline comparison regeneration. Local Kubernetes and EKS runs require
available infrastructure; unit and virtual-time tests do not replace these runs.
