# CD(GitHub Actions)가 AWS 키를 저장해 두지 않고 배포하도록 하는 설정(OIDC).
# Actions가 실행될 때마다 GitHub가 "이 저장소, 이 브랜치의 실행"이라는 증명 토큰을 발급하고,
# AWS는 그 토큰을 믿는 대가로 아래 역할의 임시 자격 증명만 내준다. 장기 액세스 키가 없어서 유출될 키 자체가 없다.
# 역할은 배포에 필요한 최소 권한(이미지 푸시, 이미지 태그 변경, 서버 교체)만 가진다.

# OIDC 공급자는 계정에 URL당 하나만 만들 수 있고 이 계정에는 이미 있다. 새로 만들지 않고 읽어서 쓴다
# (Terraform이 관리하지 않으므로 destroy해도 지워지지 않는다. 다른 계정에서 쓸 때는 resource로 바꿔 만든다)
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

data "aws_iam_policy_document" "github_assume" {
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
      values   = ["repo:${var.github_repository}:ref:refs/heads/${var.github_deploy_branch}"]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "${local.name}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_assume.json
}

data "aws_iam_policy_document" "github_deploy" {
  # 로그인 토큰 발급은 리소스를 지정할 수 없다
  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [aws_ecr_repository.app.arn]
  }

  # 배포할 태그를 담은 파라미터 하나만 읽고 쓴다
  statement {
    actions   = ["ssm:GetParameter", "ssm:PutParameter"]
    resources = ["${local.ssm_param_arn}/deploy/image_tag"]
  }

  statement {
    actions   = ["autoscaling:StartInstanceRefresh"]
    resources = [aws_autoscaling_group.app.arn]
  }

  # 진행 상황 조회는 리소스를 지정할 수 없다
  statement {
    actions   = ["autoscaling:DescribeInstanceRefreshes"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "deploy"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}
