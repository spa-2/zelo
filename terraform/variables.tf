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
  default = "c7g.large"
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
  default = "db.m7g.large"
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

# 메일을 보낼 도메인. Route53에 같은 이름의 호스팅 존이 있어야 한다(SES 인증 레코드를 거기에 만든다)
variable "mail_domain" {
  type    = string
  default = "wyshin.click"
}

# SES 샌드박스 동안 메일을 받을 수 있는 주소(인증 메일의 링크를 눌러야 활성화된다). 프로덕션 액세스 승인 후에는 비워도 된다
variable "mail_sandbox_recipients" {
  type    = list(string)
  default = []
}

variable "metrics_collector_security_group_id" {
  type        = string
  default     = null
  description = "동일 VPC의 Prometheus 수집기 보안 그룹. 지정한 경우에만 관리 포트 접근 허용"
}

# 앱이 켤 Spring 프로필. application-{값}.yaml이 base 위에 덮어쓴다 (performance: 부하 테스트용, prod: 운영용)
variable "spring_profile" {
  type    = string
  default = "performance"
}

# 성능 측정용 수집기(Prometheus + Grafana). 쓰지 않을 때는 false로 EC2와 고정 IP를 만들지 않는다
variable "monitoring_enabled" {
  type    = bool
  default = true
}

variable "monitoring_instance_type" {
  type    = string
  default = "t4g.small"
}

# 부하기(k6). 앱 VPC와 분리된 별도 VPC에 둔다. 쓰지 않을 때는 false로 EC2와 고정 IP를 만들지 않는다
variable "loadgen_enabled" {
  type    = bool
  default = true
}

# 부하기가 병목이면 결과가 의미 없다. CPU가 넉넉한(2 vCPU 이상) 인스턴스를 쓰고 부하 중 CloudWatch로 CPU를 같이 본다
variable "loadgen_instance_type" {
  type    = string
  default = "c7g.large"
}

# 앱 VPC(vpc_cidr)와 겹치지 않아야 한다
variable "loadgen_vpc_cidr" {
  type    = string
  default = "10.100.0.0/16"
}

variable "readonly_user_name" {
  description = "팀원이 함께 쓰는 읽기 전용 IAM 사용자 이름 (빈 문자열이면 만들지 않는다)"
  type        = string
  default     = "zelo"
}

# 프론트(S3 + CloudFront). 한 도메인에서 /api/*는 ALB로, 나머지는 S3로 보낸다 (frontend.tf)
variable "frontend_domain" {
  description = "프론트 접속 주소 (HTTPS)"
  type        = string
  default     = "zelo.wyshin.click"
}

variable "frontend_zone_name" {
  description = "frontend_domain의 DNS가 있는 Route53 호스팅 존 이름"
  type        = string
  default     = "wyshin.click"
}

variable "frontend_github_repository" {
  # 프론트 저장소도 OIDC 불변 subject를 써서 sub가 "소유자@ID/저장소@ID" 형식이다 (github_repository 주석 참고)
  # 값은 gh api repos/spa-2/zeloFront/actions/oidc/customization/sub 의 sub_claim_prefix에서 "repo:"를 뺀 것
  description = "S3에 프론트를 올리는(배포하는) GitHub 저장소 (소유자@ID/저장소@ID)"
  type        = string
  default     = "spa-2@342884922/zeloFront@1439170934"
}

variable "frontend_deploy_branch" {
  description = "이 브랜치에서 실행된 Actions만 프론트 배포 역할을 받을 수 있다"
  type        = string
  default     = "dev"
}

# ───────── 환경 분리(dev: 부하 테스트용 / prod: 운영). 값은 envs/*.tfvars에 둔다 ─────────
variable "create_ecr" {
  description = "false면 이미지 저장소를 만들지 않고 이미 있는 저장소를 쓴다(환경이 하나의 저장소를 공유할 때). 이미지는 한 번만 빌드해서 두 환경이 태그로 가져다 쓴다"
  type        = bool
  default     = true
}

variable "frontend_enabled" {
  description = "S3 + CloudFront(프론트 호스팅)를 만들지. 부하 테스트 환경에서는 필요 없다"
  type        = bool
  default     = true
}

variable "test_seed_parameters" {
  description = "부하 테스트 전용 SSM 파라미터(시드 개수, fake LLM 지연)를 만들지. 운영에서는 false"
  type        = bool
  default     = true
}

variable "mode_initial_value" {
  description = "LLM/PORTONE/MAIL/SOCIAL_MODE의 처음 값. 운영은 CHANGE_ME로 두면 실제 값을 넣기 전에는 앱이 시작하지 않는다(prod 프로필은 mode 기본값이 없다)"
  type        = string
  default     = "fake"
}

# ───────── 데이터 계층 가용성 ─────────
variable "db_multi_az" {
  description = "RDS 대기(standby) 서버를 다른 AZ에 둔다. 장애 때 자동 전환되고 비용은 약 2배"
  type        = bool
  default     = false
}

variable "db_backup_retention_days" {
  type    = number
  default = 1
}

variable "db_deletion_protection" {
  type    = bool
  default = false
}

variable "redis_mode" {
  description = "ec2: EC2 한 대에 Redis 컨테이너(부하 테스트용), elasticache: 관리형 ElastiCache(운영)"
  type        = string
  default     = "ec2"

  validation {
    condition     = contains(["ec2", "elasticache"], var.redis_mode)
    error_message = "ec2 또는 elasticache만 가능하다."
  }
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.micro"
}

variable "redis_replicas" {
  description = "ElastiCache 복제본 수(0이면 단일 노드). 1 이상이면 자동 장애 전환과 Multi-AZ가 켜진다"
  type        = number
  default     = 1
}

variable "redis_engine" {
  description = "redis 또는 valkey(Redis 호환). 앱이 쓰는 Redisson과의 호환을 확인하기 전에는 redis를 쓴다"
  type        = string
  default     = "redis"

  validation {
    condition     = contains(["redis", "valkey"], var.redis_engine)
    error_message = "redis 또는 valkey만 가능하다."
  }
}

# ───────── 로그와 알림 ─────────
variable "log_backend" {
  description = "loki: 수집기 EC2의 Loki + 앱 서버 Alloy(부하 테스트용), cloudwatch: CloudWatch Logs(운영용, 서버 운영 없이 보관 기간만 정책으로)"
  type        = string
  default     = "loki"

  validation {
    condition     = contains(["loki", "cloudwatch"], var.log_backend)
    error_message = "loki 또는 cloudwatch만 가능하다."
  }
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "alarm_email" {
  description = "CloudWatch 알람을 받을 이메일(비우면 알람은 만들지만 알림 주소는 없다). 구독 확인 메일의 링크를 눌러야 한다"
  type        = string
  default     = ""
}

variable "alert_channel" {
  description = "Grafana 알림을 보낼 곳(slack 또는 discord 웹훅)"
  type        = string
  default     = "slack"

  validation {
    condition     = contains(["slack", "discord"], var.alert_channel)
    error_message = "slack 또는 discord만 가능하다."
  }
}

variable "ses_enabled" {
  description = "SES 도메인 인증과 DNS 레코드를 만들지. 도메인 인증은 계정과 리전에 하나뿐이라 한 환경(운영)만 만든다. 부하 테스트 환경은 메일이 fake라 필요 없다"
  type        = bool
  default     = true
}

variable "origin_domain" {
  description = "CloudFront가 ALB에 HTTPS로 붙을 ALB 전용 도메인(예: api-origin.wyshin.click). 비우면 ALB는 HTTP로만 연다(부하 테스트). ALB 기본 주소(*.elb.amazonaws.com)에는 인증서를 못 만들어서 도메인이 따로 필요하다"
  type        = string
  default     = ""
}
