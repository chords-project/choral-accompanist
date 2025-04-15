# Copyright (c) HashiCorp, Inc.
# SPDX-License-Identifier: MPL-2.0

provider "aws" {
  alias  = "main-region"
  region = var.main_region
}

provider "aws" {
  alias  = "flight-region"
  region = var.flight_region
}

/*
# Filter out local zones, which are not currently supported
# with managed node groups
data "aws_availability_zones" "available" {
  filter {
    name = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}
*/

locals {
  cluster_name = "travel-eks-${random_string.suffix.result}"
}

resource "random_string" "suffix" {
  length  = 6
  special = false
}
