# 메일 발송(SES): 가입 인증 같은 서비스 메일을 우리 도메인(noreply@도메인)으로 보낼 수 있게 한다.
# 메일 서버가 "이 도메인에서 온 메일이 진짜"라고 믿게 하려면 도메인 소유를 증명하고 DNS에 서명 정보를 등록해야 한다.
#   1) 도메인 인증(identity): SES에 "이 도메인으로 메일을 보내겠다"고 등록한다
#   2) DKIM: 메일에 전자서명을 붙여 위조 여부를 확인하고, 검증용 공개키를 DNS(CNAME 3개)에 올린다. 이게 있어야 스팸함으로 덜 간다
#   3) DMARC: DKIM 검증에 실패한 메일을 수신자가 어떻게 처리할지 알리는 정책이다(DNS TXT 1개)
# DNS가 Route53이라서 레코드까지 Terraform이 직접 만든다.
#
# SES는 처음에 샌드박스(하루 200통, 인증한 수신 주소에게만 발송)로 시작한다.
# 일반 사용자에게 보내려면 콘솔에서 프로덕션 액세스를 신청해야 하고, 이건 Terraform으로 할 수 없다.

locals {
  # 앱이 보내는 사람 주소로 쓴다. IAM에서도 이 주소로만 보내도록 제한한다
  mail_from = "noreply@${var.mail_domain}"
}

# 이미 Route53에 만들어 둔 호스팅 존을 읽는다 (새로 만들지 않는다)
data "aws_route53_zone" "mail" {
  name         = var.mail_domain
  private_zone = false
}

# SES에 도메인을 등록한다. 등록하면 DKIM(서명) 토큰 3개가 발급된다
resource "aws_sesv2_email_identity" "mail" {
  email_identity = var.mail_domain
}

# DKIM 검증용 CNAME 3개. SES가 이 레코드를 DNS에서 확인하면 도메인이 Verified가 된다(보통 몇 분)
resource "aws_route53_record" "ses_dkim" {
  count = 3

  zone_id = data.aws_route53_zone.mail.zone_id
  name    = "${aws_sesv2_email_identity.mail.dkim_signing_attributes[0].tokens[count.index]}._domainkey.${var.mail_domain}"
  type    = "CNAME"
  ttl     = 600
  records = ["${aws_sesv2_email_identity.mail.dkim_signing_attributes[0].tokens[count.index]}.dkim.amazonses.com"]
}

# DMARC: p=none은 "검증에 실패해도 막지 말고 그대로 전달"이라서 일단 영향 없이 시작한다.
# 발송이 안정되면 quarantine(스팸함), reject(거부)로 강화한다
resource "aws_route53_record" "dmarc" {
  zone_id = data.aws_route53_zone.mail.zone_id
  name    = "_dmarc.${var.mail_domain}"
  type    = "TXT"
  ttl     = 600
  records = ["v=DMARC1; p=none;"]
}

# 샌드박스 동안 메일을 받을 수 있는 주소(내 메일 등). 등록하면 해당 주소로 인증 메일이 가고, 받은 링크를 눌러야 인증된다.
# 프로덕션 액세스가 승인되면 필요 없다. 값은 terraform.tfvars에 넣는다(커밋되지 않는다)
resource "aws_sesv2_email_identity" "sandbox_recipient" {
  for_each = toset(var.mail_sandbox_recipients)

  email_identity = each.value
}
