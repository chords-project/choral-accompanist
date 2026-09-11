# Warehouse benchmark on EKS

This configuration extends the [EKS tutorial](https://developer.hashicorp.com/terraform/tutorials/kubernetes/eks)
with Kubernetes 1.35, AL2023 nodes and immutable ECR repositories for benchmark images.

Review and provision from this directory using your AWS account:

```sh
terraform init
terraform plan -out=benchmark.tfplan
terraform apply benchmark.tfplan
aws eks update-kubeconfig --region eu-central-1 --name "$(terraform output -raw cluster_name)"
terraform output -raw skaffold_default_repo
```

Authenticate Docker to your ECR registry with `aws ecr get-login-password` and
`docker login --username AWS --password-stdin`. Use the registry prefix above with
`benchmark_deploy.py --default-repo`. See [BENCHMARK.md](../BENCHMARK.md) for deployment,
namespace creation, port forwards, collection and comparison. No public dashboard
load balancers are required. The same Kubernetes manifests run locally and on EKS.

Terraform provisioning and EKS benchmark acceptance require an AWS account and have
not been executed as part of the local implementation checks.
