# 운영용 Redis: 관리형 ElastiCache. 복제본을 두면 primary가 죽을 때 AWS가 복제본을 승격시키고(자동 장애 전환),
# 앱이 바라보는 primary 엔드포인트 주소는 그대로다. 패치, 백업, 장애 감지를 직접 하지 않아도 된다.
# redis_mode = "ec2"(부하 테스트)에서는 만들지 않는다.
# 앱(Redisson)이 TLS와 비밀번호로 접속해야 하므로 ssm.tf가 spring.data.redis.ssl.enabled=true를 같이 넣는다.

locals {
  # 엔진과 파라미터 그룹 버전은 같이 움직인다
  redis_engine_version  = var.redis_engine == "valkey" ? "8.0" : "7.1"
  redis_parameter_group = var.redis_engine == "valkey" ? "default.valkey8" : "default.redis7"
}

resource "aws_elasticache_subnet_group" "redis" {
  count = var.redis_mode == "elasticache" ? 1 : 0

  name       = "${local.name}-redis-subnets"
  subnet_ids = aws_subnet.data[*].id
}

resource "aws_elasticache_replication_group" "redis" {
  count = var.redis_mode == "elasticache" ? 1 : 0

  replication_group_id = "${local.name}-redis"
  description          = "${local.name} redis"

  engine               = var.redis_engine
  engine_version       = local.redis_engine_version
  parameter_group_name = local.redis_parameter_group
  node_type            = var.redis_node_type
  port                 = 6379

  # 복제본이 있으면 노드가 둘 이상이고 서로 다른 AZ에 놓인다
  num_cache_clusters         = 1 + var.redis_replicas
  automatic_failover_enabled = var.redis_replicas > 0
  multi_az_enabled           = var.redis_replicas > 0

  subnet_group_name  = aws_elasticache_subnet_group.redis[0].name
  security_group_ids = [aws_security_group.redis.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.redis.result

  snapshot_retention_limit = 1
  apply_immediately        = true

  tags = { Name = "${local.name}-redis" }
}
