# 데이터베이스(RDS Postgres 16): 데이터 서브넷에 두고 앱·Bastion 보안그룹에서만 접근할 수 있다.
# 마스터 비밀번호는 random_password가 만들고, ssm.tf가 SSM에 저장해서 앱이 읽는다.

resource "aws_db_subnet_group" "main" {
  name       = "${local.name}-db-subnets"
  subnet_ids = aws_subnet.data[*].id

  tags = { Name = "${local.name}-db-subnets" }
}

# Single-AZ 인스턴스지만 서브넷 그룹은 AZ 2개가 있어야 만들 수 있다
resource "aws_db_instance" "main" {
  identifier     = "${local.name}-postgres"
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  allocated_storage = var.db_allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.db_username
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = var.db_multi_az

  backup_retention_period    = var.db_backup_retention_days
  auto_minor_version_upgrade = true
  apply_immediately          = true
  deletion_protection        = var.db_deletion_protection

  # Performance Insights: 어떤 SQL이 DB 시간을 쓰는지, 대기가 CPU인지 락인지 I/O인지 보여 준다(콘솔 → RDS → 성능 개선 도우미).
  # 보관 7일은 무료이고, 7일보다 길게 잡으면 요금이 붙는다. 켜고 끄는 데 재부팅은 필요 없다
  performance_insights_enabled          = true
  performance_insights_retention_period = 7

  skip_final_snapshot = var.db_skip_final_snapshot
  # skip_final_snapshot을 나중에 false로 바꿔도 destroy가 스냅샷 이름을 갖도록 항상 지정한다 (random_id는 한 번 만들면 plan마다 바뀌지 않는다)
  final_snapshot_identifier = "${local.name}-final-${random_id.db_snapshot.hex}"
}

resource "random_id" "db_snapshot" {
  byte_length = 4
}