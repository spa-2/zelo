# CloudWatch 알람 → SNS → Lambda → Slack/Discord. 서버 밖(AWS)에서 거는 알람이라 수집기(Grafana)가 죽어도 알림이 온다.
# 웹훅 주소는 SSM 파라미터 한 곳(local.alert_webhook_parameter)을 Grafana 알림과 같이 쓴다. 주소를 넣기 전에는 아무것도 보내지 않는다.
#   1) Slack(또는 Discord)에서 웹훅 주소를 만든다  2) aws ssm put-parameter --name <파라미터> --type SecureString --overwrite --value '<주소>'
# 알람은 어떤 알림 수단이든 SNS 토픽(aws_sns_topic.alarms)에 걸려 있으므로, 카카오톡 같은 다른 수단도 이 토픽에 구독자를 추가해서 붙인다.

data "archive_file" "alarm_to_chat" {
  count = var.alert_forwarder_enabled ? 1 : 0

  type        = "zip"
  source_file = "${path.module}/lambda/alarm_to_chat.py"
  output_path = "${path.module}/.terraform/alarm_to_chat.zip"
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "alarm_to_chat" {
  count = var.alert_forwarder_enabled ? 1 : 0

  name               = "${local.name}-alarm-to-chat"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

# 로그 쓰기 권한(기본 실행 역할)
resource "aws_iam_role_policy_attachment" "alarm_to_chat_logs" {
  count = var.alert_forwarder_enabled ? 1 : 0

  role       = aws_iam_role.alarm_to_chat[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# 웹훅 주소 파라미터 하나만 읽는다
data "aws_iam_policy_document" "alarm_to_chat_ssm" {
  count = var.alert_forwarder_enabled ? 1 : 0

  statement {
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.alert_webhook.arn]
  }
}

resource "aws_iam_role_policy" "alarm_to_chat_ssm" {
  count = var.alert_forwarder_enabled ? 1 : 0

  name   = "read-webhook-parameter"
  role   = aws_iam_role.alarm_to_chat[0].id
  policy = data.aws_iam_policy_document.alarm_to_chat_ssm[0].json
}

resource "aws_cloudwatch_log_group" "alarm_to_chat" {
  count = var.alert_forwarder_enabled ? 1 : 0

  name              = "/aws/lambda/${local.name}-alarm-to-chat"
  retention_in_days = 14
}

resource "aws_lambda_function" "alarm_to_chat" {
  count = var.alert_forwarder_enabled ? 1 : 0

  function_name    = "${local.name}-alarm-to-chat"
  role             = aws_iam_role.alarm_to_chat[0].arn
  runtime          = "python3.12"
  architectures    = ["arm64"]
  handler          = "alarm_to_chat.handler"
  filename         = data.archive_file.alarm_to_chat[0].output_path
  source_code_hash = data.archive_file.alarm_to_chat[0].output_base64sha256
  timeout          = 10

  environment {
    variables = {
      WEBHOOK_PARAMETER = local.alert_webhook_parameter
      CHANNEL           = var.alert_channel
      ENVIRONMENT       = var.environment
    }
  }

  depends_on = [aws_cloudwatch_log_group.alarm_to_chat]
}

resource "aws_lambda_permission" "alarm_to_chat_sns" {
  count = var.alert_forwarder_enabled ? 1 : 0

  statement_id  = "AllowSnsInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.alarm_to_chat[0].function_name
  principal     = "sns.amazonaws.com"
  source_arn    = aws_sns_topic.alarms.arn
}

resource "aws_sns_topic_subscription" "alarm_to_chat" {
  count = var.alert_forwarder_enabled ? 1 : 0

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "lambda"
  endpoint  = aws_lambda_function.alarm_to_chat[0].arn
}
