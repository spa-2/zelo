# 성능 측정용 수집기(Prometheus + Grafana + Loki): 한 EC2에 Docker로 띄운다.
#   Prometheus는 앱 서버를 태그로 자동 탐색해서 9292(/actuator/prometheus)를 긁는다. 서버가 교체되거나 늘어나도 설정을 고칠 필요가 없다.
#   Grafana(3000)는 allowed_ssh_cidrs의 IP에만 열고, Prometheus(9090)는 서버 안에서만 접근된다.
#   Loki(3100)는 앱 서버의 Alloy가 보내는 로그를 받는다. VPC 안에서만 열린다.
# 쓰지 않을 때는 monitoring_enabled = false로 EC2와 고정 IP를 만들지 않는다.

locals {
  grafana_password_parameter = "/${var.project}-monitoring/${var.environment}/grafana_admin_password"

  # ASG가 앱 서버에 붙이는 Name 태그(ec2.tf의 aws_autoscaling_group.app)와 같아야 한다
  app_name_tag = "${local.name}-app"

  # 앱 서버의 Alloy가 로그를 보낼 Loki 주소. 수집기를 교체해도 앱 설정이 바뀌지 않도록 서브넷 안의 주소를 고정한다 (Redis와 같은 방식)
  loki_host = var.monitoring_enabled ? cidrhost(aws_subnet.public[0].cidr_block, 10) : ""

  # Grafana 알림 웹훅 주소(Slack, Discord). 처음에는 CHANGE_ME이고, 값을 넣은 뒤 zelo-alert-contact.sh를 실행한다
  alert_webhook_parameter = "/${var.project}-monitoring/${var.environment}/alert_webhook_url"

  # Loki를 쓰지 않는 환경(운영)에서는 Loki 데이터소스를 쓰는 로그 패널을 대시보드에서 뺀다 (데이터소스가 없어 오류로 보이므로)
  overview_dashboard = jsondecode(file("${path.module}/../monitoring/grafana/zelo-overview.json"))
  overview_dashboard_json = var.log_backend == "loki" ? file("${path.module}/../monitoring/grafana/zelo-overview.json") : jsonencode(merge(local.overview_dashboard, {
    panels = [for panel in local.overview_dashboard.panels : panel if try(panel.datasource.uid, "") != "loki"]
  }))
}

resource "aws_ssm_parameter" "alert_webhook" {
  name  = local.alert_webhook_parameter
  type  = "SecureString"
  value = "CHANGE_ME"

  lifecycle {
    # 콘솔이나 CLI로 넣은 실제 웹훅 주소를 다음 apply가 되돌리지 않게 한다
    ignore_changes = [value]
  }
}

resource "random_password" "grafana" {
  length  = 24
  special = false
}

# 앱이 읽는 /zelo/dev/ 경로와 섞이지 않도록 다른 경로에 둔다. state에는 평문으로 남는다
resource "aws_ssm_parameter" "grafana_password" {
  name  = local.grafana_password_parameter
  type  = "SecureString"
  value = random_password.grafana.result
}

resource "aws_security_group" "monitoring" {
  name        = "${local.name}-monitoring-sg"
  description = "Prometheus and Grafana collector"
  vpc_id      = aws_vpc.main.id

  # Grafana 접속: 개발자 IP만 (SSH 허용 목록과 같은 팀원 목록을 쓴다)
  ingress {
    description = "Grafana from developers"
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = var.allowed_ssh_cidrs
  }

  ingress {
    description     = "SSH from bastion"
    from_port       = 22
    to_port         = 22
    protocol        = "tcp"
    security_groups = [aws_security_group.bastion.id]
  }

  # 부하기(k6)가 Prometheus로 지표를 보낸다(remote write). 부하기 고정 IP 한 곳만 허용하고 인증이 없으므로 다른 곳에는 열지 않는다.
  # 부하기는 별도 VPC라서 수집기의 공인 IP로 들어온다
  dynamic "ingress" {
    for_each = var.loadgen_enabled ? [1] : []

    content {
      description = "Prometheus remote write from the k6 load generator"
      from_port   = 9090
      to_port     = 9090
      protocol    = "tcp"
      cidr_blocks = ["${aws_eip.loadgen[0].public_ip}/32"]
    }
  }

  # 앱 서버의 로그 수집기(Alloy)가 Loki(3100)로 로그를 보낸다. 앱 보안그룹과 서로 참조하면 순환이 생겨서 VPC 안쪽 주소로 연다 (외부에는 열지 않는다)
  dynamic "ingress" {
    for_each = var.log_backend == "loki" ? [1] : []

    content {
      description = "Loki from inside the VPC"
      from_port   = 3100
      to_port     = 3100
      protocol    = "tcp"
      cidr_blocks = [aws_vpc.main.cidr_block]
    }
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.name}-monitoring-sg" }
}

# EC2 탐색(DescribeInstances)은 리소스 단위 제한이 불가능해서 "*"로 둔다 (읽기 전용 조회)
data "aws_iam_policy_document" "monitoring" {
  statement {
    actions   = ["ec2:DescribeInstances"]
    resources = ["*"]
  }

  statement {
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.grafana_password.arn, aws_ssm_parameter.alert_webhook.arn]
  }

  # Grafana의 CloudWatch 데이터소스(지표와 Logs Insights) 조회. 읽기 전용이고 리소스 단위로 제한하기 어려운 항목이 많다
  statement {
    actions = [
      "cloudwatch:DescribeAlarmsForMetric",
      "cloudwatch:DescribeAlarmHistory",
      "cloudwatch:DescribeAlarms",
      "cloudwatch:ListMetrics",
      "cloudwatch:GetMetricData",
      "cloudwatch:GetMetricStatistics",
      "logs:DescribeLogGroups",
      "logs:DescribeLogStreams",
      "logs:GetLogGroupFields",
      "logs:StartQuery",
      "logs:StopQuery",
      "logs:GetQueryResults",
      "logs:GetLogEvents",
      "logs:FilterLogEvents",
      "ec2:DescribeTags",
      "ec2:DescribeRegions",
      "tag:GetResources",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "monitoring" {
  name   = "prometheus-discovery-and-grafana-password"
  role   = aws_iam_role.ec2["monitoring"].id
  policy = data.aws_iam_policy_document.monitoring.json
}

resource "aws_instance" "monitoring" {
  count = var.monitoring_enabled ? 1 : 0

  ami                    = data.aws_ssm_parameter.al2023_ami.value
  instance_type          = var.monitoring_instance_type
  subnet_id              = aws_subnet.public[0].id
  private_ip             = local.loki_host
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.monitoring.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2["monitoring"].name

  # 설정과 대시보드가 합치면 user_data 한도(16KB)를 넘을 수 있어서 압축한다 (cloud-init이 자동으로 푼다)
  user_data_base64 = base64gzip(templatefile("${path.module}/templates/monitoring_user_data.sh.tftpl", {
    region                     = var.region
    grafana_password_parameter = local.grafana_password_parameter
    prometheus_config = templatefile("${path.module}/templates/prometheus.yml.tftpl", {
      region          = var.region
      management_port = var.management_port
      app_name_tag    = local.app_name_tag
    })
    loki_enabled            = var.log_backend == "loki"
    alert_channel           = var.alert_channel
    alert_webhook_parameter = local.alert_webhook_parameter
    dashboard_json          = local.overview_dashboard_json
    k6_dashboard_json       = file("${path.module}/../monitoring/grafana/zelo-k6.json")
    # 부하기가 있을 때만 밖으로 열고 k6 지표를 받는다
    prometheus_listen_address = var.loadgen_enabled ? "0.0.0.0:9090" : "127.0.0.1:9090"
    remote_write_enabled      = var.loadgen_enabled
  }))
  # 설정이 바뀌면 부팅 스크립트를 다시 돌려야 하므로 교체한다. 수집기는 일회용이다
  user_data_replace_on_change = true

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true
  }

  tags = { Name = "${local.name}-monitoring" }

  lifecycle {
    ignore_changes = [ami]
  }
}

# Grafana 주소가 재시작 후에도 바뀌지 않게 고정 IP를 붙인다
resource "aws_eip" "monitoring" {
  count = var.monitoring_enabled ? 1 : 0

  domain   = "vpc"
  instance = aws_instance.monitoring[0].id

  tags = { Name = "${local.name}-monitoring-eip" }
}
