# 서버들: Bastion(SSH 관문), Redis(단일 EC2에 컨테이너), 앱(Launch Template + Auto Scaling Group).
# 앱은 부팅할 때 user_data로 배포 스크립트(templates/)를 실행해 SSM의 image_tag 이미지를 띄운다.
# ASG는 인스턴스 수가 min 미만이 되면 같은 설정으로 다시 만들어서 자동 복구한다.

data "aws_ssm_parameter" "al2023_ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

locals {
  deploy_script = templatefile("${path.module}/templates/zelo-deploy.sh.tftpl", {
    region            = var.region
    prefix            = local.ssm_prefix
    repository_url    = aws_ecr_repository.app.repository_url
    app_port          = var.app_port
    management_port   = var.management_port
    health_check_path = var.health_check_path
  })
}

resource "aws_instance" "bastion" {
  ami                    = data.aws_ssm_parameter.al2023_ami.value
  instance_type          = var.bastion_instance_type
  subnet_id              = aws_subnet.public[0].id
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.bastion.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2["bastion"].name

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
    encrypted   = true
  }

  tags = { Name = "${local.name}-bastion" }

  lifecycle {
    # 최신 AMI가 나왔다고 인스턴스가 교체되지 않게 함
    ignore_changes = [ami]
  }
}

# 중지 후 재시작해도 접속 주소가 바뀌지 않게 고정 IP를 붙인다
resource "aws_eip" "bastion" {
  domain   = "vpc"
  instance = aws_instance.bastion.id

  tags = { Name = "${local.name}-bastion-eip" }
}

# 단일 인스턴스다. Redis가 죽으면 로그인 유지(refresh), 번호표, 락이 모두 영향을 받는다.
# AOF를 켜서 재부팅에는 데이터가 남지만, 인스턴스를 교체하면 사라진다.
resource "aws_instance" "redis" {
  ami                    = data.aws_ssm_parameter.al2023_ami.value
  instance_type          = var.redis_instance_type
  subnet_id              = aws_subnet.data[0].id
  private_ip             = local.redis_private_ip
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.redis.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2["redis"].name

  user_data = templatefile("${path.module}/templates/redis_user_data.sh.tftpl", {
    region = var.region
    prefix = local.ssm_prefix
  })

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 16
    encrypted   = true
  }

  tags = { Name = "${local.name}-redis" }

  # 부팅 시 비밀번호 파라미터를 읽으므로 먼저 만들어져야 하고, 이미지를 받으려면 NAT 경로가 있어야 한다
  depends_on = [aws_ssm_parameter.redis_password, aws_route_table_association.data]

  lifecycle {
    # 교체되면 Redis 데이터가 사라지므로 AMI 갱신만으로는 교체하지 않는다
    ignore_changes = [ami]
  }
}

resource "aws_launch_template" "app" {
  name_prefix   = "${local.name}-app-"
  image_id      = "resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
  instance_type = var.app_instance_type
  key_name      = var.key_name

  iam_instance_profile {
    name = aws_iam_instance_profile.ec2["app"].name
  }

  vpc_security_group_ids = [aws_security_group.app.id]

  user_data = base64encode(templatefile("${path.module}/templates/app_user_data.sh.tftpl", {
    deploy_script = local.deploy_script
  }))

  metadata_options {
    http_tokens = "required"
    # 컨테이너 안의 앱이 인스턴스 역할로 SSM 파라미터를 읽어야 한다. Docker 브릿지 네트워크는 hop이 하나 더 필요해서 2로 둔다
    http_put_response_hop_limit = 2
  }

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_type = "gp3"
      volume_size = 20
      encrypted   = true
    }
  }

  tag_specifications {
    resource_type = "instance"
    tags          = { Name = "${local.name}-app" }
  }
}

# 인스턴스가 min 미만으로 떨어지면 같은 설정으로 다시 띄운다. 새 인스턴스도 부팅 시 최신 이미지 태그를 받아 실행한다
resource "aws_autoscaling_group" "app" {
  name                = "${local.name}-app-asg"
  min_size            = var.asg_min_size
  desired_capacity    = var.asg_desired_capacity
  max_size            = var.asg_max_size
  vpc_zone_identifier = aws_subnet.private[*].id
  target_group_arns   = [aws_lb_target_group.app.arn]

  health_check_type         = var.asg_health_check_type
  health_check_grace_period = 300

  launch_template {
    id      = aws_launch_template.app.id
    version = aws_launch_template.app.latest_version
  }

  # 새 인스턴스가 정상이 된 뒤에 기존 인스턴스를 내린다 (launch template이 바뀌면 자동으로 롤링 교체)
  instance_refresh {
    strategy = "Rolling"

    preferences {
      min_healthy_percentage = 100
      max_healthy_percentage = 200
    }
  }

  tag {
    key                 = "Name"
    value               = "${local.name}-app"
    propagate_at_launch = true
  }

  # 부팅 스크립트가 파라미터를 읽고 패키지를 받으므로 둘 다 준비된 뒤에 인스턴스를 띄운다
  depends_on = [
    aws_ssm_parameter.manual,
    aws_ssm_parameter.generated,
    aws_ssm_parameter.image_tag,
    aws_route_table_association.private,
    aws_instance.redis,
  ]
}