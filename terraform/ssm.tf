# 앱 설정과 시크릿 저장소(SSM Parameter Store).
#   Terraform이 값을 아는 것(DB 주소·비밀번호, Redis 주소)은 만들어서 넣고,
#   사람이 넣어야 하는 시크릿(JWT, OAuth 키 등)은 CHANGE_ME로 틀만 만든다(ignore_changes로 실제 값 보호).
# 앱은 Spring Cloud AWS로 /zelo/dev/ 아래를 읽고, 파라미터 이름이 곧 Spring 프로퍼티 이름이다.
# 배포할 이미지 태그(deploy/image_tag)도 여기에 둔다.

locals {
  # 앱이 Spring Cloud AWS로 이 경로 아래의 파라미터를 읽는다. 파라미터 이름이 곧 Spring 프로퍼티 이름이다
  ssm_prefix = "/${var.project}/${var.environment}"

  # 서브넷 안에서 주소를 고정해 두면 Redis 인스턴스를 교체해도 앱이 바라보는 호스트 값이 바뀌지 않는다
  redis_private_ip = cidrhost(aws_subnet.data[0].cidr_block, 10)

  # 값은 콘솔이나 CLI로 직접 넣는다. 초기값 그대로면 앱이 기동하지 못하는 것이 의도다 (잘못된 값으로 조용히 뜨는 것보다 낫다)
  manual_parameters = {
    # 파라미터 이름은 application.yaml의 ${...} 이름과 대소문자까지 정확히 같아야 한다. 프로퍼티 소스에서는 대소문자를 구분한다
    JWT_SECRET             = "CHANGE_ME"
    OPENAI_API_KEY         = "CHANGE_ME"
    GOOGLE_CLIENT_ID       = "CHANGE_ME"
    GOOGLE_CLIENT_SECRET   = "CHANGE_ME"
    KAKAO_CLIENT_ID        = "CHANGE_ME"
    KAKAO_CLIENT_SECRET    = "CHANGE_ME"
    PORTONE_API_SECRET     = "CHANGE_ME"
    PORTONE_STORE_ID       = "CHANGE_ME"
    PORTONE_WEBHOOK_SECRET = "CHANGE_ME"
    # real은 OpenAI를 호출하고 fake는 호출 없이 고정된 응답을 돌려주는 모드. application.yaml의 기본값은 fake라서 서버에서는 real로 명시한다
    LLM_MODE = "real"
    # fake는 포트원 키 없이 동작하는 모드. 실제 결제를 붙일 때 real로 바꾼다
    PORTONE_MODE = "fake"
    # fake는 메일을 보내지 않고 로그에만 남기는 모드. SES 프로덕션 액세스 승인 후 real로 바꾼다
    MAIL_MODE = "fake"
    # fake는 구글, 카카오 호출 없이 "fake-*" code를 로그인으로 받아 주는 모드라 운영에서는 절대 쓰면 안 된다. 소셜 로그인을 실제로 켤 때 real로 바꾼다
    SOCIAL_MODE = "fake"
  }
}

# DB와 Redis 비밀번호는 사람이 정하지 않고 생성한다. state에는 평문으로 남으므로 state 파일은 커밋하지 않는다
resource "random_password" "db" {
  length  = 24
  special = false
}

resource "random_password" "redis" {
  length  = 32
  special = false
}

resource "aws_ssm_parameter" "manual" {
  for_each = local.manual_parameters

  name  = "${local.ssm_prefix}/${each.key}"
  type  = "SecureString"
  value = each.value

  lifecycle {
    # 콘솔에서 넣은 실제 값을 다음 apply가 초기값으로 되돌리지 않게 함
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "redis_password" {
  name  = "${local.ssm_prefix}/spring.data.redis.password"
  type  = "SecureString"
  value = random_password.redis.result
}

resource "aws_ssm_parameter" "generated" {
  for_each = {
    "spring.datasource.url"      = { type = "String", value = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${var.db_name}" }
    "spring.datasource.username" = { type = "String", value = var.db_username }
    "spring.datasource.password" = { type = "SecureString", value = random_password.db.result }
    "spring.data.redis.host"     = { type = "String", value = local.redis_private_ip }
    "zelo.mail.from"             = { type = "String", value = local.mail_from }
  }

  name  = "${local.ssm_prefix}/${each.key}"
  type  = each.value.type
  value = each.value.value
}

# 배포할 이미지 태그. 앱 설정과 섞이지 않도록 하위 경로(deploy/)에 둔다.
# 배포 파이프라인이 이 값을 바꾸고 인스턴스를 교체하거나 배포 스크립트를 실행한다.
resource "aws_ssm_parameter" "image_tag" {
  name  = "${local.ssm_prefix}/deploy/image_tag"
  type  = "String"
  value = "none"

  lifecycle {
    ignore_changes = [value]
  }
}