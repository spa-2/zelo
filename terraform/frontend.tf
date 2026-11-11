# 프론트 호스팅: 브라우저 → CloudFront(HTTPS) → S3(화면 파일) 또는 ALB(/api/*)
# 한 도메인(var.frontend_domain)으로 화면과 API를 같이 받기 때문에 브라우저 입장에서는 같은 출처다.
# 그래서 CORS 설정이 필요 없고, HTTPS 인증서도 CloudFront 하나로 끝난다.
#
# 화면 파일은 프론트 저장소(zeloFront)의 GitHub Actions가 S3에 올리고 CloudFront 캐시를 무효화한다.
# 올리는 권한은 아래 frontend_deploy 역할(OIDC)만 갖는다. 사용법은 README의 "프론트 배포" 참고.

data "aws_route53_zone" "frontend" {
  count = var.frontend_enabled ? 1 : 0

  name         = var.frontend_zone_name
  private_zone = false
}

# ───────── 화면 파일을 두는 S3 (비공개) ─────────
# 버킷은 공개하지 않고 CloudFront(OAC)만 읽게 한다. destroy할 때 파일이 있어도 지워지도록 force_destroy를 켠다
# (화면 파일은 프론트 저장소에서 다시 올리면 되는 복원 가능한 데이터다).
resource "aws_s3_bucket" "frontend" {
  count = var.frontend_enabled ? 1 : 0

  bucket        = "${local.name}-frontend-${data.aws_caller_identity.current.account_id}"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  count = var.frontend_enabled ? 1 : 0

  bucket                  = aws_s3_bucket.frontend[0].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_cloudfront_origin_access_control" "frontend" {
  count = var.frontend_enabled ? 1 : 0

  name                              = "${local.name}-frontend"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_iam_policy_document" "frontend_bucket" {
  count = var.frontend_enabled ? 1 : 0

  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.frontend[0].arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    # 이 배포(distribution)만 읽을 수 있다
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.frontend[0].arn]
    }
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  count = var.frontend_enabled ? 1 : 0

  bucket = aws_s3_bucket.frontend[0].id
  policy = data.aws_iam_policy_document.frontend_bucket[0].json

  depends_on = [aws_s3_bucket_public_access_block.frontend[0]]
}

# ───────── HTTPS 인증서 (CloudFront용은 us-east-1에 있어야 한다) ─────────
resource "aws_acm_certificate" "frontend" {
  count = var.frontend_enabled ? 1 : 0

  provider          = aws.us_east_1
  domain_name       = var.frontend_domain
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "frontend_cert_validation" {
  for_each = var.frontend_enabled ? {
    for option in aws_acm_certificate.frontend[0].domain_validation_options : option.domain_name => {
      name   = option.resource_record_name
      type   = option.resource_record_type
      record = option.resource_record_value
    }
  } : {}

  zone_id         = data.aws_route53_zone.frontend[0].zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "frontend" {
  count = var.frontend_enabled ? 1 : 0

  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.frontend[0].arn
  validation_record_fqdns = [for record in aws_route53_record.frontend_cert_validation : record.fqdn]
}

# ───────── CloudFront ─────────
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# 헤더(Authorization 포함), 쿼리 문자열, 쿠키를 빠짐없이 ALB로 전달한다. API는 캐시하지 않는다
data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

# 화면 경로 새로고침 대응(SPA 폴백). 파일 확장자가 없는 주소(/products/1 같은 화면 경로)는 index.html로 바꿔서 받는다.
# 403/404 오류 응답을 index.html로 바꾸는 방식(custom_error_response)은 배포 전체에 적용되어
# /api/* 의 실제 403, 404 응답까지 index.html(200)로 덮어써서 API 오류가 사라지므로 쓰지 않는다.
# 이 함수는 기본 경로(S3)에만 붙고 /api/* 에는 붙지 않는다.
resource "aws_cloudfront_function" "spa_fallback" {
  count = var.frontend_enabled ? 1 : 0

  name    = "${local.name}-spa-fallback"
  runtime = "cloudfront-js-2.0"
  publish = true
  comment = "확장자 없는 경로를 /index.html로 바꾼다"
  code    = <<-EOT
    function handler(event) {
      var request = event.request;
      if (request.uri.indexOf('.') === -1) {
        request.uri = '/index.html';
      }
      return request;
    }
  EOT
}

resource "aws_cloudfront_distribution" "frontend" {
  count = var.frontend_enabled ? 1 : 0

  enabled             = true
  is_ipv6_enabled     = true
  http_version        = "http2and3"
  default_root_object = "index.html"
  aliases             = [var.frontend_domain]
  comment             = "${local.name} frontend"
  # 한국이 들어 있는 가장 저렴한 가격 등급 (북미, 유럽, 아시아)
  price_class = "PriceClass_200"

  origin {
    origin_id                = "s3"
    domain_name              = aws_s3_bucket.frontend[0].bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend[0].id
  }

  # CloudFront → ALB 구간은 HTTP다 (AWS가 인터넷으로 보내는 구간이라 임시 구성이다).
  # ALB에 HTTPS를 붙이면 https-only로 바꾸고 ALB 보안그룹을 CloudFront에서 오는 요청만 받게 좁힌다
  origin {
    origin_id   = "alb"
    domain_name = aws_lb.main.dns_name

    custom_origin_config {
      http_port                = 80
      https_port               = 443
      origin_protocol_policy   = "http-only"
      origin_ssl_protocols     = ["TLSv1.2"]
      origin_read_timeout      = 60
      origin_keepalive_timeout = 5
    }
  }

  # 기본: 화면 파일(S3)
  default_cache_behavior {
    target_origin_id       = "s3"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_fallback[0].arn
    }
  }

  # /api/* 는 ALB(백엔드)로. 캐시하지 않고 모든 메서드를 허용한다
  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = "alb"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.frontend[0].certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

# 도메인 → CloudFront (IPv4, IPv6)
resource "aws_route53_record" "frontend_a" {
  count = var.frontend_enabled ? 1 : 0

  zone_id = data.aws_route53_zone.frontend[0].zone_id
  name    = var.frontend_domain
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.frontend[0].domain_name
    zone_id                = aws_cloudfront_distribution.frontend[0].hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "frontend_aaaa" {
  count = var.frontend_enabled ? 1 : 0

  zone_id = data.aws_route53_zone.frontend[0].zone_id
  name    = var.frontend_domain
  type    = "AAAA"

  alias {
    name                   = aws_cloudfront_distribution.frontend[0].domain_name
    zone_id                = aws_cloudfront_distribution.frontend[0].hosted_zone_id
    evaluate_target_health = false
  }
}

# ───────── 프론트 저장소(zeloFront)의 배포 권한 (OIDC, 저장된 액세스 키 없음) ─────────
# 백엔드 배포 역할(github_oidc.tf)과 같은 방식이고, 저장소와 브랜치가 다르므로 역할을 따로 둔다.
data "aws_iam_policy_document" "frontend_deploy_assume" {
  count = var.frontend_enabled ? 1 : 0

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # 다른 저장소, 다른 브랜치, PR 실행은 이 역할을 받을 수 없다
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.frontend_github_repository}:ref:refs/heads/${var.frontend_deploy_branch}"]
    }
  }
}

resource "aws_iam_role" "frontend_deploy" {
  count = var.frontend_enabled ? 1 : 0

  name               = "${local.name}-frontend-deploy"
  assume_role_policy = data.aws_iam_policy_document.frontend_deploy_assume[0].json
}

# 화면 버킷에 올리고 지우는 권한과 캐시 무효화 권한만 준다
data "aws_iam_policy_document" "frontend_deploy" {
  count = var.frontend_enabled ? 1 : 0

  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.frontend[0].arn]
  }

  statement {
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.frontend[0].arn}/*"]
  }

  statement {
    actions   = ["cloudfront:CreateInvalidation"]
    resources = [aws_cloudfront_distribution.frontend[0].arn]
  }
}

resource "aws_iam_role_policy" "frontend_deploy" {
  count = var.frontend_enabled ? 1 : 0

  name   = "frontend-deploy"
  role   = aws_iam_role.frontend_deploy[0].id
  policy = data.aws_iam_policy_document.frontend_deploy[0].json
}
