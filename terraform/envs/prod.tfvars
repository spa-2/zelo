# 운영 환경. 부하 테스트 환경과 상태 파일(workspace)을 분리해서 쓴다.
# 사용: terraform workspace new prod   (이미 있으면 terraform workspace select prod)
#       terraform plan  -var-file=envs/prod.tfvars
#       terraform apply -var-file=envs/prod.tfvars
# 팀원 IP, 키 페어 같은 개인 값은 terraform.tfvars(커밋되지 않음)에 둔다.
environment    = "prod"
spring_profile = "prod"

# 프론트: https://zelo.wyshin.click (S3 + CloudFront, /api/* 는 ALB로)
frontend_enabled = true
frontend_domain  = "zelo.wyshin.click"

# 이미지 저장소와 SES 도메인 인증은 계정에 하나뿐이라 운영이 만든다. 읽기 전용 콘솔 계정도 계정 전체에서 이름이 하나라 부하 테스트 환경 쪽에서 만든다
create_ecr         = true
ses_enabled        = true
readonly_user_name = ""

# 시크릿과 모드는 CHANGE_ME로 시작한다. 모드(LLM/PORTONE/MAIL/SOCIAL)에 실제 값(real)을 넣기 전에는 앱이 시작하지 않는다.
# 부하 테스트 전용 시드와 fake LLM 설정은 만들지 않는다
test_seed_parameters = false
mode_initial_value   = "CHANGE_ME"

# 로그는 CloudWatch Logs(보관 30일), Redis는 ElastiCache(복제본 1), DB는 Multi-AZ
log_backend              = "cloudwatch"
log_retention_days       = 30
redis_mode               = "elasticache"
redis_replicas           = 1
db_multi_az              = true
db_backup_retention_days = 7
db_deletion_protection   = true
db_skip_final_snapshot   = false
db_instance_class        = "db.m7g.large"

asg_min_size          = 2
asg_desired_capacity  = 2
asg_max_size          = 4
asg_health_check_type = "ELB"

# 부하기는 만들지 않는다. 수집기(Prometheus, Grafana)는 유지한다
monitoring_enabled = true
loadgen_enabled    = false

# CloudWatch 알람을 받을 이메일(구독 확인 메일의 링크를 눌러야 한다)
# alarm_email = "me@example.com"
# Grafana 알림 채널: slack 또는 discord. 웹훅 주소는 SSM(/zelo-monitoring/prod/alert_webhook_url)에 넣고 수집기에서 zelo-alert-contact.sh를 실행한다
alert_channel = "slack"

# ALB HTTPS: ap-northeast-2의 ACM 인증서 ARN을 넣으면 80은 443으로 리다이렉트된다.
# CloudFront → ALB 구간을 HTTPS로 하려면 ALB용 도메인(예: api-origin.wyshin.click)과 그 인증서가 필요하다(아직 구현 전).
# certificate_arn = ""

# 이 브랜치에서 실행된 GitHub Actions만 배포 역할을 받는다
github_deploy_branch   = "main"
frontend_deploy_branch = "main"
