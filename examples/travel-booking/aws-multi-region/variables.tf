# Copyright (c) HashiCorp, Inc.
# SPDX-License-Identifier: MPL-2.0

variable "main_region" {
  description = "Main AWS region"
  type        = string
  default     = "eu-central-1" # Frankfurt
}

variable "flight_region" {
  description = "Flight AWS region"
  type        = string
  default     = "eu-west-3" # Paris
}
