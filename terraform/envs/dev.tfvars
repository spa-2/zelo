# 부하 테스트(개발) 환경: 필요할 때 만들고 끝나면 destroy한다.
# 사용: terraform workspace select dev   (없으면 terraform workspace new dev)
#       terraform apply -var-file=envs/dev.tfvars
# 팀원 IP, 키 페어 같은 개인 값은 terraform.tfvars(커밋되지 않음)에 둔다.
environment    = "dev"
spring_profile = "performance"

# 부하 테스트에는 프론트(S3, CloudFront)와 메일 도메인 인증이 필요 없다.
# 이미지 저장소는 운영 환경이 만든 것을 같이 쓴다(운영 환경을 먼저 만들어야 한다).
frontend_enabled = false
ses_enabled      = false
create_ecr       = false
# 나중에 부하 테스트 환경에도 화면을 올린다면 이 주소를 쓴다
frontend_domain = "test.zelo.wyshin.click"

# 부하 테스트 전용 시드와 fake 설정, 외부 서비스는 fake
test_seed_parameters = true
mode_initial_value   = "fake"

# 로그는 수집기 EC2의 Loki, Redis는 EC2 컨테이너, DB는 단일 AZ (구성은 단순하게, 성능 영향이 작은 부분이라 운영과 달라도 된다)
log_backend              = "loki"
redis_mode               = "ec2"
db_multi_az              = false
db_backup_retention_days = 1
db_deletion_protection   = false
db_skip_final_snapshot   = true

monitoring_enabled = true
loadgen_enabled    = true

github_deploy_branch   = "dev"
frontend_deploy_branch = "dev"
