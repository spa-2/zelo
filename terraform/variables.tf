# 입력 변수(variable) 선언: 환경마다 달라질 값(CIDR, 인스턴스 사양, 허용 IP 등)을 코드 밖에서 바꿀 수 있게 모아 둔다.
# default가 없는 변수(allowed_ssh_cidrs, key_name)는 terraform.tfvars나 -var로 반드시 넣어야 한다.

variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "project" {
  type    = string
  default = "zelo"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "azs" {
  type    = list(string)
  default = ["ap-northeast-2a", "ap-northeast-2c"]
}

variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  type    = list(string)
  default = ["10.0.0.0/24", "10.0.1.0/24"]
}

variable "private_subnet_cidrs" {
  type    = list(string)
  default = ["10.0.10.0/24", "10.0.11.0/24"]
}

variable "data_subnet_cidrs" {
  type    = list(string)
  default = ["10.0.20.0/24", "10.0.21.0/24"]
}

# 필수 입력: 기본값을 두면 실수로 전체 공개될 수 있어서 default를 두지 않는다
variable "allowed_ssh_cidrs" {
  type        = list(string)
  description = "Bastion SSH를 허용할 개발자 IP 목록 (예: [\"203.0.113.10/32\"])"

  validation {
    condition     = length(var.allowed_ssh_cidrs) > 0 && !contains(var.allowed_ssh_cidrs, "0.0.0.0/0")
    error_message = "SSH를 전체 공개(0.0.0.0/0)할 수 없다. 개발자 IP를 /32로 하나 이상 지정할 것."
  }
}

# 콘솔에서 미리 만들어 둔 키 페어 이름. 키를 Terraform으로 만들면 개인키가 state에 남아서 콘솔에서 만든다
variable "key_name" {
  type        = string
  description = "Bastion에 사용할 기존 EC2 키 페어 이름"
}

variable "app_instance_type" {
  type    = string
  default = "t4g.small"
}

variable "bastion_instance_type" {
  type    = string
  default = "t4g.nano"
}

variable "redis_instance_type" {
  type    = string
  default = "t4g.micro"
}

variable "app_port" {
  type    = number
  default = 8080
}

# application.yaml의 management.server.port. actuator(헬스체크, prometheus)가 앱 포트와 다른 포트로 열린다
variable "management_port" {
  type    = number
  default = 9292
}

variable "asg_min_size" {
  type    = number
  default = 1
}

variable "asg_desired_capacity" {
  type    = number
  default = 1
}

variable "asg_max_size" {
  type    = number
  default = 2
}

# 첫 배포(이미지 푸시) 전에는 ELB로 두면 앱이 없어서 헬스체크 실패 → 인스턴스가 계속 교체된다.
# 앱이 정상 기동한 것을 확인한 뒤 "ELB"로 바꾼다.
variable "asg_health_check_type" {
  type    = string
  default = "EC2"

  validation {
    condition     = contains(["EC2", "ELB"], var.asg_health_check_type)
    error_message = "EC2 또는 ELB만 가능하다."
  }
}

# 관리 포트(management_port)로 확인한다
variable "health_check_path" {
  type    = string
  default = "/actuator/health"
}

# 비어 있으면 HTTP(80)만 사용하고, ACM 인증서 ARN을 넣으면 80은 443으로 리다이렉트된다
variable "certificate_arn" {
  type    = string
  default = ""
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "db_allocated_storage" {
  type    = number
  default = 20
}

variable "db_name" {
  type    = string
  default = "zelo"
}

variable "db_username" {
  type    = string
  default = "zelo"
}

# 개발 환경에서는 destroy가 막히지 않도록 true. 데이터가 필요하면 false로 바꾼다
variable "db_skip_final_snapshot" {
  type    = bool
  default = true
}

# CD가 AWS 역할을 받을 수 있는 저장소와 브랜치. 신뢰 조건에 그대로 들어간다
# 저장소가 OIDC 불변 subject(use_immutable_subject)를 쓰면 토큰의 sub가 "소유자@ID/저장소@ID" 형식이라 ID까지 맞춰야 한다
# 값은 gh api repos/<소유자>/<저장소>/actions/oidc/customization/sub 의 sub_claim_prefix에서 "repo:"를 뺀 것
variable "github_repository" {
  type    = string
  default = "spa-2@342884922/zelo@1420323810"
}

variable "github_deploy_branch" {
  type    = string
  default = "dev"
}
