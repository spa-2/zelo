# Terraform 자체 설정: 어떤 클라우드(provider)를 어떤 버전으로 쓸지, 어느 리전에 만들지를 정한다.
# default_tags는 만들어지는 모든 AWS 리소스에 자동으로 붙는 태그이고(비용 추적용),
# locals.name(zelo-dev)은 다른 파일들이 리소스 이름 접두사로 같이 쓴다.

terraform {
  required_version = ">= 1.10" # use_lockfile이 1.10 이상 필요

  backend "s3" {
    bucket       = "zelo-tfstate-567378238963"
    key          = "zelo/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true # S3 자체 잠금(DynamoDB 불필요)
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

locals {
  name = "${var.project}-${var.environment}"
}

# CloudFront에 붙이는 인증서(ACM)는 반드시 버지니아 북부(us-east-1)에 있어야 한다 (frontend.tf)
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
