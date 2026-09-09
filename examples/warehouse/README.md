# Warehouse SAGA Example

## Run using docker compose

To run this example, first build the docker images by running `./gradlew jibDockerBuild`.
This will build and install the docker images `accompanist-warehouse`, `accompanist-payment`, and
`accompanist-loyalty`.

Now run the docker compose file by running `docker compose -f compose.accompanist.yml up`.
This starts all containers with their databases and the pinned LGTM backend.
Open Grafana at <http://localhost:3000>.
OTLP is exposed at `http://localhost:4317` (gRPC) and `http://localhost:4318` (HTTP).

To trigger the choreography, run `curl localhost:5000/orderFulfillment`.

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

## Observability data lifetime

The Kubernetes LGTM Deployment deliberately uses named `emptyDir` volumes.
Its data disappears when the Pod is replaced.

Docker Compose uses the named `lgtm-data` volume.

## Grafana dashboards

The Kubernetes deployment provisions dashboards from
`observability/grafana/dashboards` into the **Accompanist** Grafana folder. Kustomize packages the
dashboard and provider configuration as ConfigMaps, so the same manifests work in a local cluster
and when deployed to Kubernetes on AWS.

For live dashboard development, update the JSON and upload it to the running Grafana instance
without restarting the cluster:

```shell
./observability/grafana/upload-dashboard.sh
```

The script defaults to `http://localhost:3000` and the Accompanist overview dashboard. Pass another
dashboard file as its first argument or set `GRAFANA_URL`. For authenticated Grafana instances, set
either `GRAFANA_TOKEN` or `GRAFANA_USER` and `GRAFANA_PASSWORD`.
