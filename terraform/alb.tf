# 로드밸런서(ALB): 인터넷 요청을 받아 앱 서버(타겟 그룹)로 나눠 보내고, 헬스체크로 죽은 서버를 제외한다.
# 인증서가 있으면(origin_domain을 정하거나 certificate_arn을 넣으면, alb_https.tf) 80은 443으로 리다이렉트되고 HTTPS로 전환된다.

resource "aws_lb" "main" {
  name                       = "${local.name}-alb"
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = aws_subnet.public[*].id
  drop_invalid_header_fields = true

  tags = { Name = "${local.name}-alb" }
}

resource "aws_lb_target_group" "app" {
  name                 = "${local.name}-app"
  port                 = var.app_port
  protocol             = "HTTP"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30

  health_check {
    path                = var.health_check_path
    port                = var.management_port
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# 80 포트 리스너는 하나만 두고 동작만 바꾼다. 인증서가 없으면 바로 앱으로 보내고, 있으면 443으로 리다이렉트한다.
# (forward용과 redirect용을 별개 리소스로 두면 HTTPS를 켤 때 같은 포트에 리스너를 새로 만들다가 기존 것이 지워지기 전에 DuplicateListener로 실패한다)
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  dynamic "default_action" {
    for_each = local.alb_https_enabled ? [] : [1]

    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.app.arn
    }
  }

  dynamic "default_action" {
    for_each = local.alb_https_enabled ? [1] : []

    content {
      type = "redirect"

      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }
}

resource "aws_lb_listener" "https" {
  count = local.alb_https_enabled ? 1 : 0

  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = local.alb_certificate_arn

  # 비밀 헤더 확인을 켠 환경에서는 헤더가 없는 요청(CloudFront를 거치지 않은 직접 접근)을 403으로 막는다.
  # 헤더가 맞는 요청은 aws_lb_listener_rule.origin_verify가 앱으로 보낸다
  dynamic "default_action" {
    for_each = local.origin_verify ? [1] : []

    content {
      type = "fixed-response"

      fixed_response {
        content_type = "text/plain"
        message_body = "Forbidden"
        status_code  = "403"
      }
    }
  }

  dynamic "default_action" {
    for_each = local.origin_verify ? [] : [1]

    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.app.arn
    }
  }
}
