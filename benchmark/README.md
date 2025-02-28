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
