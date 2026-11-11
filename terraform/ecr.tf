# 도커 이미지 저장소(ECR): CI가 빌드한 이미지를 올리면 앱 서버가 받아서 실행한다.
# 수명 정책으로 오래된 이미지는 자동 삭제한다.
# 저장소는 환경이 공유한다. 한쪽 환경(보통 prod)이 만들고(create_ecr = true), 다른 환경은 만들지 않고(false) 같은 저장소를 읽는다.
# 한 번 빌드한 이미지를 환경마다 SSM image_tag 값으로 골라 배포한다. force_delete가 켜져 있으니 저장소를 만든 환경을 destroy하면 이미지가 같이 지워진다.

resource "aws_ecr_repository" "app" {
  count = var.create_ecr ? 1 : 0

  name                 = var.project
  image_tag_mutability = "MUTABLE"
  # 개발 환경에서 이미지가 남아 있어도 destroy가 되도록 함
  force_delete = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

# 저장소를 이 환경이 만들지 않을 때는 이미 있는 저장소를 이름으로 찾는다
data "aws_ecr_repository" "shared" {
  count = var.create_ecr ? 0 : 1
  name  = var.project
}

locals {
  ecr_repository_url = var.create_ecr ? aws_ecr_repository.app[0].repository_url : data.aws_ecr_repository.shared[0].repository_url
  ecr_repository_arn = var.create_ecr ? aws_ecr_repository.app[0].arn : data.aws_ecr_repository.shared[0].arn
}

resource "aws_ecr_lifecycle_policy" "app" {
  count = var.create_ecr ? 1 : 0

  repository = aws_ecr_repository.app[0].name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "최근 이미지 10개만 유지"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
