# 성능 측정용 수집기(Prometheus + Grafana): 한 EC2에 Docker로 띄운다.
#   Prometheus는 앱 서버를 태그로 자동 탐색해서 9292(/actuator/prometheus)를 긁는다. 서버가 교체되거나 늘어나도 설정을 고칠 필요가 없다.
#   Grafana(3000)는 allowed_ssh_cidrs의 IP에만 열고, Prometheus(9090)는 서버 안에서만 접근된다.
# 쓰지 않을 때는 monitoring_enabled = false로 EC2와 고정 IP를 만들지 않는다.

locals {
  grafana_password_parameter = "/${var.project}-monitoring/${var.environment}/grafana_admin_password"

  # ASG가 앱 서버에 붙이는 Name 태그(ec2.tf의 aws_autoscaling_group.app)와 같아야 한다
  app_name_tag = "${local.name}-app"
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
    resources = [aws_ssm_parameter.grafana_password.arn]
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
    dashboard_json = file("${path.module}/../monitoring/grafana/zelo-overview.json")
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
