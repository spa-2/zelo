# Terraform 자체 설정: 어떤 클라우드(provider)를 어떤 버전으로 쓸지, 어느 리전에 만들지를 정한다.
# default_tags는 만들어지는 모든 AWS 리소스에 자동으로 붙는 태그이고(비용 추적용),
# locals.name(zelo-dev)은 다른 파일들이 리소스 이름 접두사로 같이 쓴다.

terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
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
