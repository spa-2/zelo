# 도커 이미지 저장소(ECR): CI가 빌드한 이미지를 올리면 앱 서버가 받아서 실행한다.
# 수명 정책으로 오래된 이미지는 자동 삭제한다.

resource "aws_ecr_repository" "app" {
  name                 = var.project
  image_tag_mutability = "MUTABLE"
  # 개발 환경에서 이미지가 남아 있어도 destroy가 되도록 함
  force_delete = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

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