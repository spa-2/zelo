# ALB의 HTTPS와 CloudFront → ALB 구간의 암호화(origin_domain을 정하면 켜진다).
#
#   사용자 ─HTTPS─▶ CloudFront ─HTTPS(origin_domain)─▶ ALB(443) ─▶ 앱
#
# - ALB에 ACM 인증서를 붙이고 DNS(Route53)에서 origin_domain이 ALB를 가리키게 한다. 80은 443으로 리다이렉트된다.
# - ALB 기본 주소(*.elb.amazonaws.com)로는 인증서를 만들 수 없어서 CloudFront는 origin_domain으로 ALB에 붙는다.
#   이때 viewer의 Host 헤더(zelo.wyshin.click)를 그대로 보내면 ALB 인증서와 어긋나서, Host는 origin_domain으로 두는 정책을 쓴다(frontend.tf).
# - ALB 주소로 CloudFront를 거치지 않고 직접 들어오는 요청은 막는다. CloudFront가 비밀 헤더(X-Origin-Verify)를 붙여서 보내고,
#   ALB는 이 헤더가 맞는 요청만 앱으로 보낸다. 헤더가 없으면 403이다. (CloudFront 전용 접근 제한을 보안그룹의 prefix list로 하면
#   보안그룹 규칙 한도(기본 60)를 55개씩 먹어서, 헤더 방식을 쓴다.)
# 부하 테스트(dev)는 origin_domain이 비어 있어서 HTTP로 ALB에 직접 붙는다.

locals {
  origin_https         = var.origin_domain != ""
  origin_verify        = local.origin_https && var.frontend_enabled
  alb_https_enabled    = local.origin_https || var.certificate_arn != ""
  alb_certificate_arn  = local.origin_https ? aws_acm_certificate_validation.alb[0].certificate_arn : var.certificate_arn
  origin_verify_header = "X-Origin-Verify"
}

data "aws_route53_zone" "origin" {
  count = local.origin_https ? 1 : 0

  name         = var.frontend_zone_name
  private_zone = false
}

resource "aws_acm_certificate" "alb" {
  count = local.origin_https ? 1 : 0

  domain_name       = var.origin_domain
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "alb_cert_validation" {
  for_each = local.origin_https ? {
    for option in aws_acm_certificate.alb[0].domain_validation_options : option.domain_name => {
      name   = option.resource_record_name
      type   = option.resource_record_type
      record = option.resource_record_value
    }
  } : {}

  zone_id         = data.aws_route53_zone.origin[0].zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "alb" {
  count = local.origin_https ? 1 : 0

  certificate_arn         = aws_acm_certificate.alb[0].arn
  validation_record_fqdns = [for record in aws_route53_record.alb_cert_validation : record.fqdn]
}

# origin_domain → ALB
resource "aws_route53_record" "alb_origin" {
  count = local.origin_https ? 1 : 0

  zone_id = data.aws_route53_zone.origin[0].zone_id
  name    = var.origin_domain
  type    = "A"

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}

# CloudFront와 ALB만 아는 비밀 값. state에는 평문으로 남는다
resource "random_password" "origin_verify" {
  count = local.origin_verify ? 1 : 0

  length  = 40
  special = false
}

# 비밀 헤더가 맞는 요청만 앱으로 보낸다
resource "aws_lb_listener_rule" "origin_verify" {
  count = local.origin_verify ? 1 : 0

  listener_arn = aws_lb_listener.https[0].arn
  priority     = 1

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }

  condition {
    http_header {
      http_header_name = local.origin_verify_header
      values           = [random_password.origin_verify[0].result]
    }
  }
}
