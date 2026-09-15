# Warehouse benchmark on EKS

This configuration extends the [EKS tutorial](https://developer.hashicorp.com/terraform/tutorials/kubernetes/eks)
with Kubernetes 1.35, AL2023 nodes, a default EBS CSI `gp3` storage class and immutable
ECR repositories for benchmark images.

Review and provision from this directory using your AWS account:

```sh
terraform init
terraform plan -out=benchmark.tfplan
terraform apply benchmark.tfplan
aws eks update-kubeconfig --region eu-central-1 --name "$(terraform output -raw cluster_name)"
terraform output -raw skaffold_default_repo
```

Use the registry prefix above with `benchmark_deploy.py --default-repo`. The deployment
helper recognizes private ECR repository URLs and obtains a temporary Docker login with
the AWS CLI before building and pushing `linux/amd64` images for the x86-64 node group.
Architecture-qualified tags keep these builds distinct from native Apple Silicon images
in the immutable repositories. See [BENCHMARK.md](../BENCHMARK.md) for deployment, port
forwards, collection and comparison. The helper creates or reuses its dedicated namespace
automatically. No public dashboard load balancers are required. The same Kubernetes
manifests run locally and on EKS.

Terraform provisioning and EKS benchmark acceptance require an AWS account and have
not been executed as part of the local implementation checks.
