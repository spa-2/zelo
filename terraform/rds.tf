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
  multi_az               = false

  backup_retention_period    = 1
  auto_minor_version_upgrade = true
  apply_immediately          = true
  deletion_protection        = false

  skip_final_snapshot       = var.db_skip_final_snapshot
  final_snapshot_identifier = var.db_skip_final_snapshot ? null : "${local.name}-final-${formatdate("YYYYMMDDhhmm", timestamp())}"

  lifecycle {
    # timestamp()가 매 plan마다 달라 보이는 것을 막는다
    ignore_changes = [final_snapshot_identifier]
  }
}