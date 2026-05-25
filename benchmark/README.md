# Benchmark

Make sure to run a `jaeger` docker container to collect tracing data.

```bash
$ docker run --rm --name jaeger \
  -p 4317:4317 \
  -p 16686:16686 \
  jaegertracing/all-in-one:1.62.0
```

The dashboard can then be accessed at http://localhost:16686

Run `gradle run` to run all services in a single java execution.

## Simulated Latency

Build the docker benchmark into a Docker image.

```bash
$ ./gradlew jibDockerBuild
```

Then start the Docker compose environment.

```bash
$ cd simulated-latency/
$ docker compose up -d
```

The `benchmark` container will run an automated benchmark of chain length 1, 3, 5, for both Accompanist and the orchestrator.
When the benchmark is done, it will print the results as CSV data to stdout.

Use this command to read the logs of the benchmark.

```bash
$ docker compose logs benchmark -f
```

