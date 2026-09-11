The shared benchmark uses pinned Kubernetes manifests; see [BENCHMARK.md](../../BENCHMARK.md). This optional Helm path is not part of its validated configuration.

In order to deploy temporal using the official Helm package run the following command.

```shell
helm install --version 1.6.0 --repo https://go.temporal.io/helm-charts -f helm.values.yml temporal temporal
```
