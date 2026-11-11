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

  # Redis 접속 주소. EC2(부하 테스트용)는 서브넷 안의 고정 주소, ElastiCache(운영)는 기본(primary) 엔드포인트다.
  # ElastiCache는 장애 전환이 일어나도 이 주소가 새 primary를 가리킨다
  redis_host = var.redis_mode == "elasticache" ? aws_elasticache_replication_group.redis[0].primary_endpoint_address : local.redis_private_ip

  # ElastiCache는 접속 비밀번호(AUTH)를 쓰려면 전송 구간 암호화(TLS)가 필수라서 앱이 TLS로 접속하게 한다
  redis_ssl_parameters = var.redis_mode == "elasticache" ? {
    "spring.data.redis.ssl.enabled" = { type = "String", value = "true" }
  } : {}

  # 값은 콘솔이나 CLI로 직접 넣는다. 초기값 그대로면 앱이 기동하지 못하는 것이 의도다 (잘못된 값으로 조용히 뜨는 것보다 낫다)
  secret_parameters = {
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
  }

  # real은 외부 서비스를 실제로 호출하고 fake는 호출 없이 고정된 응답을 돌려주는 모드. 처음 값은 var.mode_initial_value다.
  #   부하 테스트(dev)는 fake(외부 호출 없음, 비용 방지), 운영(prod)은 CHANGE_ME로 두고 실제 값(real)을 넣는다.
  #   CHANGE_ME는 enum으로 읽을 수 없어서 값을 넣기 전에는 앱이 시작하지 않는다 (fake로 조용히 뜨는 것을 막는 안전장치).
  #   LLM: real은 OpenAI 호출, PORTONE: real은 실제 결제, MAIL: real은 SES 발송(프로덕션 액세스 승인 후),
  #   SOCIAL: fake는 "fake-*" code를 로그인으로 받아 주므로 운영에서 절대 쓰면 안 된다
  mode_parameters = {
    LLM_MODE     = var.mode_initial_value
    PORTONE_MODE = var.mode_initial_value
    MAIL_MODE    = var.mode_initial_value
    SOCIAL_MODE  = var.mode_initial_value
  }

  # 부하 테스트 전용. 운영에서는 만들지 않는다(var.test_seed_parameters = false)
  test_parameters = var.test_seed_parameters ? {
    # 통합 부하 테스트용 fake LLM 설정(앱의 FakeLlmGateway). LLM_MODE=fake일 때만 쓰이고 real이면 무시된다.
    # 실측 지연을 랜덤으로 흉내 내고(DELAY), 견적까지 가는 의도로 돌려서 부하가 실제와 비슷해지게 한다. 통합 테스트가 끝나면 지운다
    "zelo.llm.fake.mode"             = "DELAY"
    "zelo.llm.fake.plan-intent"      = "SHOPPING_REQUEST"
    "zelo.llm.fake.operation-delays" = "MENU_PLAN:1100ms-3600ms,QUOTE_EXPLAIN:1000ms-1800ms,QUOTE_EDIT_PLAN:1100ms-2200ms,LOOKUP_ANSWER:2600ms-4100ms,CHAT_ANSWER:1400ms-2300ms"
    "zelo.llm.fake.intent-keywords"  = "QUOTE_EDIT:빼,LOOKUP_QUESTION:리뷰,GENERAL_QUESTION:보관"
  } : {}

  manual_parameters = merge(local.secret_parameters, local.mode_parameters, local.test_parameters)
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
  for_each = merge({
    "spring.datasource.url"      = { type = "String", value = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${var.db_name}" }
    "spring.datasource.username" = { type = "String", value = var.db_username }
    "spring.datasource.password" = { type = "SecureString", value = random_password.db.result }
    "spring.data.redis.host"     = { type = "String", value = local.redis_host }
    "zelo.mail.from"             = { type = "String", value = local.mail_from }
  }, local.redis_ssl_parameters)

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