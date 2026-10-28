# apply가 끝나면 터미널에 출력할 값(ALB 주소, ECR 주소, Bastion IP 등). terraform output 으로 다시 볼 수 있다.

output "alb_dns_name" {
  value = aws_lb.main.dns_name
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "bastion_public_ip" {
  value = aws_eip.bastion.public_ip
}

output "rds_endpoint" {
  value = aws_db_instance.main.address
}

output "redis_private_ip" {
  value = local.redis_private_ip
}

output "ssm_prefix" {
  value = local.ssm_prefix
}
# GitHub 저장소 변수 AWS_DEPLOY_ROLE_ARN에 넣는 값
output "github_deploy_role_arn" {
  value = aws_iam_role.github_deploy.arn
}
