# Copyright (c) HashiCorp, Inc.
# SPDX-License-Identifier: MPL-2.0

terraform {

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }

    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }

    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.4"
    }

    cloudinit = {
      source  = "hashicorp/cloudinit"
      version = "~> 2.4"
    }

    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 3.2"
    }
  }

  required_version = ">= 1.5.7, < 2.0.0"
}
