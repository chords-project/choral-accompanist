# Warehouse SAGA Example

## Run using docker compose

To run this example, first build the docker images by running `gradle jibDockerBuild`.
This will build and install the docker containers `accompanist-warehouse`, `accompanist-payment`, and
`accompanist-loyalty`.

Now run the docker compose file by running `docker compose -f compose.accompanist.yml up`.
This starts all containers with their databases and the pinned LGTM backend.
Open Grafana at <http://localhost:3000>.
OTLP is exposed at `http://localhost:4317` (gRPC) and `http://localhost:4318` (HTTP).

## Fault-tolerance evaluation on Kubernetes

The Locust class picker offers both `WebshopChoreographyUser` for the ordinary
benchmark and `FaultToleranceWarehouseUser` for the fault-tolerance benchmark.
When a fault-tolerance test starts, the load-generator Pod scales the `payment`
Deployment to zero after 120 seconds and restores it 60 seconds later.

```shell
skaffold dev
```

Skaffold starts and port-forwards the complete setup without provider-specific
manifest changes. Wait for the `lgtm` Pod readiness probe, then open Grafana at
<http://localhost:3000> and Locust at <http://localhost:8089>. The warehouse
endpoint is <http://localhost:5000/orderFulfillment>. Cluster-internal OTLP
endpoints are `http://lgtm:4317` (gRPC) and `http://lgtm:4318` (HTTP).

The timing can be changed in `loadgenerator/deployment.yaml` via
`FAULT_AFTER_SECONDS` and `FAULT_DURATION_SECONDS`. `REQUEST_PERIOD_SECONDS`
uses Locust's constant post-request delay; this is a closed, fixed-concurrency
workload, so offered throughput can fall while requests are blocked. The controller
writes exact test and scale timestamps to `/tmp/fault_events.csv`; Locust writes
request data under `/tmp/fault-tolerance*.csv`. Copy all results before deleting
the Pod:

```shell
pod=$(kubectl get pod -l app=loadgenerator -o jsonpath='{.items[0].metadata.name}')
kubectl cp "$pod:/tmp/fault_events.csv" ./fault_events.csv
kubectl cp "$pod:/tmp/fault-tolerance_stats_history.csv" ./fault-tolerance_stats_history.csv
```

If `FAULT_DEPLOYMENT` is changed, update the Role's `resourceNames` entry to the
same Deployment. Keeping that list explicit prevents the benchmark Pod from
scaling unrelated workloads.

## Observability data lifetime

The Kubernetes LGTM Deployment deliberately uses named `emptyDir` volumes.
Its data disappears when the Pod is replaced.

Docker Compose uses the named `lgtm-data` volume.
Before treating LGTM as the only copy of evaluation results,
replace the Kubernetes `emptyDir` volumes with PVCs (one per named volume, using the cluster's storage class)
or export the required metrics and traces before teardown.
The pinned image and endpoint/resource settings are recorded in `observability/evaluation-metadata.yaml`.
