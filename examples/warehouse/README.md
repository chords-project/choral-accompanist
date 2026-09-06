# Warehouse SAGA Example

## Run using docker compose

To run this example, first build the docker images by running `gradle jibDockerBuild`.
This will build and install the docker containers `accompanist-warehouse`, `accompanist-payment`, and
`accompanist-loyalty`.

Now run the docker compose file by running `docker compose -f compose.accompanist.yml up`.
This will start all containers with their respective databases.

## Fault-tolerance evaluation on Kubernetes

The Locust class picker offers both `WebshopChoreographyUser` for the ordinary
benchmark and `FaultToleranceWarehouseUser` for the fault-tolerance benchmark.
When a fault-tolerance test starts, the load-generator Pod scales the `payment`
Deployment to zero after 120 seconds and restores it 60 seconds later. Its RBAC
permissions are limited to the `payment` Deployment's scale subresource. Select
only one user class for a run.

Run it against Docker Desktop Kubernetes:

```shell
kubectl config use-context docker-desktop
skaffold dev
```

Open <http://localhost:8089>, select `FaultToleranceWarehouseUser`, choose a
constant number of users and spawn rate, and start the test. Run for at least
300 seconds to capture baseline, outage, and recovery. Stop the test from Locust
after the recovery period. Selecting `WebshopChoreographyUser` runs the original
benchmark without injecting a fault.

The same command works for EKS after selecting its kubectl context and providing
an ECR repository for Skaffold-built images:

```shell
skaffold dev \
  --default-repo ACCOUNT_ID.dkr.ecr.REGION.amazonaws.com/choral-accompanist
```

The timing can be changed in `loadgenerator/deployment.yaml` via
`FAULT_AFTER_SECONDS` and `FAULT_DURATION_SECONDS`. `REQUEST_PERIOD_SECONDS`
uses Locust's constant pacing to keep the offered load stable. The controller
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
