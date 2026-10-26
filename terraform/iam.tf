# 권한(IAM): EC2 인스턴스가 AWS API(SSM 파라미터 읽기, ECR 이미지 받기)를 호출할 수 있게 역할(role)을 만들고
# 인스턴스 프로파일로 서버에 붙인다. 앱/Redis/Bastion 역할을 나눠서 각자 필요한 권한만 준다.

data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

locals {
  ssm_param_arn = "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter${local.ssm_prefix}"

  ssm_core = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"

  # AmazonSSMManagedInstanceCore는 Session Manager용이고 Parameter Store 값 읽기 권한은 포함하지 않는다.
  # 읽기 권한은 아래 인라인 정책으로 따로 준다.
  role_policies = {
    app     = [local.ssm_core, "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"]
    redis   = [local.ssm_core]
    bastion = [local.ssm_core]
  }

  role_attachments = merge([
    for role, arns in local.role_policies : {
      for arn in arns : "${role}-${basename(arn)}" => { role = role, arn = arn }
    }
  ]...)
}

resource "aws_iam_role" "ec2" {
  for_each = local.role_policies

  name               = "${local.name}-${each.key}-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "managed" {
  for_each = local.role_attachments

  role       = aws_iam_role.ec2[each.value.role].name
  policy_arn = each.value.arn
}

# 기본 키(aws/ssm)로 암호화한 SecureString은 IAM에 kms:Decrypt를 따로 주지 않아도 읽을 수 있다.
# 고객 관리형 KMS 키로 바꾸면 해당 키의 kms:Decrypt 권한을 추가해야 한다.
data "aws_iam_policy_document" "app_ssm_read" {
  statement {
    actions   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
    resources = [local.ssm_param_arn, "${local.ssm_param_arn}/*"]
  }
}

# Redis 서버는 자기 비밀번호 하나만 읽으면 된다
data "aws_iam_policy_document" "redis_ssm_read" {
  statement {
    actions   = ["ssm:GetParameter"]
    resources = ["${local.ssm_param_arn}/spring.data.redis.password"]
  }
}

resource "aws_iam_role_policy" "app_ssm_read" {
  name   = "ssm-parameter-read"
  role   = aws_iam_role.ec2["app"].id
  policy = data.aws_iam_policy_document.app_ssm_read.json
}

resource "aws_iam_role_policy" "redis_ssm_read" {
  name   = "ssm-parameter-read"
  role   = aws_iam_role.ec2["redis"].id
  policy = data.aws_iam_policy_document.redis_ssm_read.json
}

resource "aws_iam_instance_profile" "ec2" {
  for_each = local.role_policies

  name = "${local.name}-${each.key}-profile"
  role = aws_iam_role.ec2[each.key].name
}