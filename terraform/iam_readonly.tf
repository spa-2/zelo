# 팀원이 함께 쓰는 읽기 전용 IAM 사용자(기본 이름 zelo). 콘솔에서 EC2, RDS, CloudWatch 등을 볼 수 있지만
# 만들기, 수정, 삭제는 할 수 없다. 여러 사람이 동시에 로그인해도 된다(IAM 사용자는 동시 세션 제한이 없다).
# 한 계정을 같이 쓰므로 CloudTrail에서 누가 했는지는 구분되지 않는다.

locals {
  readonly_enabled = var.readonly_user_name != ""
}

resource "aws_iam_user" "readonly" {
  count = local.readonly_enabled ? 1 : 0
  name  = var.readonly_user_name
}

resource "aws_iam_user_policy_attachment" "readonly_access" {
  count      = local.readonly_enabled ? 1 : 0
  user       = aws_iam_user.readonly[0].name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# 콘솔 로그인. 같이 쓰는 계정이라 첫 로그인 때 변경을 강제하지 않는다
# (한 명이 바꾸면 나머지 사람의 비밀번호가 달라져서 로그인이 안 된다).
resource "aws_iam_user_login_profile" "readonly" {
  count                   = local.readonly_enabled ? 1 : 0
  user                    = aws_iam_user.readonly[0].name
  password_reset_required = false

  lifecycle {
    ignore_changes = [password_reset_required, password_length]
  }
}
