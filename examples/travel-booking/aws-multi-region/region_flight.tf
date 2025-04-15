module "flight_vpc" {
  providers = {
    aws = aws.flight-region
  }

  source  = "terraform-aws-modules/vpc/aws"
  version = "5.8.1"

  name = "travel-booking-vpc"

  cidr = "10.0.0.0/16"
  //azs = slice(data.aws_availability_zones.available.names, 0, 3)
  azs = ["eu-west-3a", "eu-west-3b", "eu-west-3c"]

  private_subnets = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  public_subnets = ["10.0.4.0/24", "10.0.5.0/24", "10.0.6.0/24"]

  enable_nat_gateway   = true
  single_nat_gateway   = true
  enable_dns_hostnames = true

  public_subnet_tags = {
    "kubernetes.io/role/elb" = 1
  }

  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = 1
  }
}

module "flight_eks" {
  providers = {
    aws = aws.flight-region
  }

  source  = "terraform-aws-modules/eks/aws"
  version = "20.8.5"

  cluster_name    = "flight-${local.cluster_name}"
  cluster_version = "1.29"

  cluster_endpoint_public_access           = true
  enable_cluster_creator_admin_permissions = true

  vpc_id     = module.flight_vpc.vpc_id
  subnet_ids = module.flight_vpc.private_subnets

  eks_managed_node_group_defaults = {
    ami_type = "AL2_x86_64"
  }

  eks_managed_node_groups = {
    one = {
      name = "node-group-1"

      instance_types = ["m5.large"]

      min_size     = 1
      max_size     = 1
      desired_size = 1
    }
  }
}