# Copyright (c) HashiCorp, Inc.
# SPDX-License-Identifier: MPL-2.0

output "main_cluster_endpoint" {
  description = "Endpoint for Main EKS control plane"
  value       = module.main_eks.cluster_endpoint
}

output "main_cluster_security_group_id" {
  description = "Main Security group ids attached to the cluster control plane"
  value       = module.main_eks.cluster_security_group_id
}

output "main_region" {
  description = "Main AWS region"
  value       = var.main_region
}

output "main_cluster_name" {
  description = "Main Kubernetes Cluster Name"
  value       = module.main_eks.cluster_name
}

output "flight_cluster_endpoint" {
  description = "Endpoint for Flight EKS control plane"
  value       = module.flight_eks.cluster_endpoint
}

output "flight_cluster_security_group_id" {
  description = "Flight Security group ids attached to the cluster control plane"
  value       = module.flight_eks.cluster_security_group_id
}

output "flight_region" {
  description = "Flight AWS region"
  value       = var.flight_region
}

output "flight_cluster_name" {
  description = "Flight Kubernetes Cluster Name"
  value       = module.flight_eks.cluster_name
}
