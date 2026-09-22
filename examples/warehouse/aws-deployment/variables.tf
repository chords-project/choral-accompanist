# Copyright (c) HashiCorp, Inc.
# SPDX-License-Identifier: MPL-2.0

variable "region" {
  description = "AWS region"
  type        = string
  default     = "eu-central-1"
}

variable "kubernetes_version" {
  description = "Pinned Kubernetes minor; verify regional EKS support before provisioning"
  type        = string
  default     = "1.36"
}
