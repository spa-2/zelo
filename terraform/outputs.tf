# apply가 끝나면 터미널에 출력할 값(ALB 주소, ECR 주소, Bastion IP 등). terraform output 으로 다시 볼 수 있다.

output "alb_dns_name" {
  value = aws_lb.main.dns_name
}

output "ecr_repository_url" {
  value = local.ecr_repository_url
}

output "bastion_public_ip" {
  value = aws_eip.bastion.public_ip
}

output "rds_endpoint" {
  value = aws_db_instance.main.address
}

output "redis_private_ip" {
  value = local.redis_host
}

output "ssm_prefix" {
  value = local.ssm_prefix
}
# GitHub 저장소 변수 AWS_DEPLOY_ROLE_ARN에 넣는 값
output "github_deploy_role_arn" {
  value = aws_iam_role.github_deploy.arn
}

# SES 도메인 인증 상태는 콘솔(SES > Identities)에서 Verified인지 확인한다
output "mail_from_address" {
  value = local.mail_from
}

output "grafana_url" {
  value = var.monitoring_enabled ? "http://${aws_eip.monitoring[0].public_ip}:3000" : null
}

# Grafana 로그인 계정은 admin, 비밀번호는 아래 SSM 파라미터에서 읽는다
output "grafana_password_parameter" {
  value = local.grafana_password_parameter
}

# 부하기 접속 주소. 접속해서 load-test/ 폴더를 복사한 뒤 ALB 주소(alb_dns_name)로 k6를 실행한다
output "loadgen_public_ip" {
  value = var.loadgen_enabled ? aws_eip.loadgen[0].public_ip : null
}

output "readonly_console_login_url" {
  description = "읽기 전용 계정이 로그인하는 콘솔 주소 (계정 ID, 사용자 이름, 비밀번호를 입력한다)"
  value       = var.readonly_user_name != "" ? "https://${data.aws_caller_identity.current.account_id}.signin.aws.amazon.com/console" : null
}

output "readonly_user_name" {
  value = var.readonly_user_name != "" ? aws_iam_user.readonly[0].name : null
}

output "readonly_password" {
  description = "읽기 전용 계정의 비밀번호. terraform output -raw readonly_password"
  value       = var.readonly_user_name != "" ? aws_iam_user_login_profile.readonly[0].password : null
  sensitive   = true
}

output "frontend_url" {
  value = var.frontend_enabled ? "https://${var.frontend_domain}" : null
}

output "frontend_bucket" {
  description = "프론트 저장소의 Actions가 화면 파일을 올리는 S3 버킷"
  value       = var.frontend_enabled ? aws_s3_bucket.frontend[0].bucket : null
}

output "frontend_distribution_id" {
  description = "배포 후 캐시를 무효화할 CloudFront 배포 ID"
  value       = var.frontend_enabled ? aws_cloudfront_distribution.frontend[0].id : null
}

output "frontend_deploy_role_arn" {
  description = "프론트 저장소 Actions가 OIDC로 받는 역할 (저장소 변수 AWS_ROLE_ARN에 넣는다)"
  value       = var.frontend_enabled ? aws_iam_role.frontend_deploy[0].arn : null
}

output "prometheus_remote_write_url" {
  description = "부하기에서 k6 지표를 보낼 주소. PROM_RW_URL=<이 값> load-test/run-k6.sh ..."
  value       = var.monitoring_enabled && var.loadgen_enabled ? "http://${aws_eip.monitoring[0].public_ip}:9090/api/v1/write" : null
}
