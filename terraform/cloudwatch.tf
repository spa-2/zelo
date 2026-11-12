# 운영의 로그와 알림은 AWS 관리형 서비스를 쓴다.
#   로그: 앱 컨테이너 로그를 CloudWatch Logs로 보낸다(log_backend = "cloudwatch"). 서버를 따로 운영하지 않고, 수집기 서버가 죽어도 로그가 남고,
#         보관 기간은 정책(log_retention_days)으로 건다. 수집량이 많으면 수집 요금이 늘므로 운영은 info 레벨을 유지한다.
#   알림: CloudWatch 알람 → SNS → 이메일. 수집기(Grafana)가 죽으면 Grafana 알림도 같이 멈추므로, 서버가 죽거나 5xx가 늘거나 DB가 포화되는 핵심 알림은
#         서버 밖(CloudWatch)에서 건다. Grafana는 대시보드와 앱 수준 알림(응답 시간, 에러 로그)에 쓴다.
# 부하 테스트(log_backend = "loki")에서는 로그 그룹을 만들지 않는다. 알람은 환경과 상관없이 만들어도 비용이 거의 없다.

locals {
  log_group_name = "/${var.project}/${var.environment}/app"
}

resource "aws_cloudwatch_log_group" "app" {
  count = var.log_backend == "cloudwatch" ? 1 : 0

  name              = local.log_group_name
  retention_in_days = var.log_retention_days
}

# 앱 서버가 로그 스트림을 만들고 로그를 쓰는 권한만 준다 (로그 그룹은 Terraform이 만든다)
data "aws_iam_policy_document" "app_logs" {
  count = var.log_backend == "cloudwatch" ? 1 : 0

  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.app[0].arn}:*"]
  }
}

resource "aws_iam_role_policy" "app_logs" {
  count = var.log_backend == "cloudwatch" ? 1 : 0

  name   = "cloudwatch-logs-write"
  role   = aws_iam_role.ec2["app"].id
  policy = data.aws_iam_policy_document.app_logs[0].json
}

# ───────── 알림: CloudWatch 알람 → SNS → 이메일 ─────────
resource "aws_sns_topic" "alarms" {
  name = "${local.name}-alarms"
}

# 구독 확인 메일의 링크를 눌러야 알림이 온다. Slack, Discord, 카카오톡은 이 토픽에 중계 함수(Lambda)나 AWS Chatbot을 붙여서 확장한다
resource "aws_sns_topic_subscription" "email" {
  count = var.alarm_email != "" ? 1 : 0

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

locals {
  alarm_actions = [aws_sns_topic.alarms.arn]
}

# 정상 서버가 하나도 없거나 일부가 비정상이면 서비스가 줄어든 것이다
resource "aws_cloudwatch_metric_alarm" "unhealthy_hosts" {
  alarm_name          = "${local.name}-unhealthy-hosts"
  alarm_description   = "ALB 대상 중 비정상 서버가 있다"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
    TargetGroup  = aws_lb_target_group.app.arn_suffix
  }
}

# 앱이 아니라 ALB 자체가 내는 5xx(502, 503, 504)와 앱이 낸 5xx를 따로 본다
resource "aws_cloudwatch_metric_alarm" "target_5xx" {
  alarm_name          = "${local.name}-app-5xx"
  alarm_description   = "앱이 5xx 응답을 많이 낸다 (5분 합계)"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 20
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "elb_5xx" {
  alarm_name          = "${local.name}-alb-5xx"
  alarm_description   = "ALB가 502, 503, 504를 낸다 (대상이 없거나 응답하지 못함)"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_ELB_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${local.name}-rds-cpu"
  alarm_description   = "DB CPU가 계속 높다"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }
}

resource "aws_cloudwatch_metric_alarm" "rds_storage" {
  alarm_name          = "${local.name}-rds-free-storage"
  alarm_description   = "DB 남은 저장 공간이 2GB 미만이다"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 2147483648
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }
}

# ElastiCache 알람은 노드 단위 지표라서 primary(-001)를 본다. 장애 전환으로 primary가 바뀌어도 이름은 노드 번호를 따라간다
resource "aws_cloudwatch_metric_alarm" "redis_cpu" {
  count = var.redis_mode == "elasticache" ? 1 : 0

  alarm_name          = "${local.name}-redis-engine-cpu"
  alarm_description   = "Redis 엔진 CPU가 계속 높다 (Redis는 한 스레드로 명령을 처리한다)"
  namespace           = "AWS/ElastiCache"
  metric_name         = "EngineCPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  dimensions = {
    CacheClusterId = "${aws_elasticache_replication_group.redis[0].replication_group_id}-001"
  }
}

resource "aws_cloudwatch_metric_alarm" "redis_memory" {
  count = var.redis_mode == "elasticache" ? 1 : 0

  alarm_name          = "${local.name}-redis-memory"
  alarm_description   = "Redis 메모리 사용률이 높다 (가득 차면 키가 지워지거나 쓰기가 실패한다)"
  namespace           = "AWS/ElastiCache"
  metric_name         = "DatabaseMemoryUsagePercentage"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  dimensions = {
    CacheClusterId = "${aws_elasticache_replication_group.redis[0].replication_group_id}-001"
  }
}

# ───────── 로그에서 지표 만들기: ERROR 로그 수 ─────────
# 앱 로그 규칙상 ERROR는 "예상하지 못한 문제"다(스택트레이스 포함). 로그를 직접 보지 않아도 늘어나면 알림이 오게 한다.
# 패턴 " ERROR "는 앞뒤에 공백이 있는 ERROR 토큰만 잡는다(로그 레벨 위치). 메시지 안의 다른 단어는 잡지 않는다.
resource "aws_cloudwatch_log_metric_filter" "app_error" {
  count = var.log_backend == "cloudwatch" ? 1 : 0

  name           = "${local.name}-app-error"
  log_group_name = aws_cloudwatch_log_group.app[0].name
  pattern        = "\" ERROR \""

  metric_transformation {
    name          = "ErrorLogCount"
    namespace     = "Zelo/App"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "app_error_logs" {
  count = var.log_backend == "cloudwatch" ? 1 : 0

  alarm_name          = "${local.name}-app-error-logs"
  alarm_description   = "앱이 ERROR 로그를 남겼다 (예상하지 못한 예외). Logs Insights에서 filter @message like /ERROR/ 로 확인한다"
  namespace           = "Zelo/App"
  metric_name         = "ErrorLogCount"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = var.error_log_alarm_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  depends_on = [aws_cloudwatch_log_metric_filter.app_error]
}
