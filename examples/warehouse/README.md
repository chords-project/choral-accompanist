For the shared Accompanist/Temporal recovery benchmark, see [BENCHMARK.md](BENCHMARK.md).

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

The default load-generator image runs `RecoveryBenchmarkUser`, with one fixed-rate
scheduler and a timed payment outage. Configure and start runs in the Locust web UI.
Use the guarded deployment and collection commands in [BENCHMARK.md](BENCHMARK.md)
for both Accompanist and Temporal. The ordinary response-paced workload is still
available by running `locust -f locustfile.py` separately.

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
