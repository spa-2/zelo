# 네트워크 뼈대: VPC → 서브넷(퍼블릭/프라이빗/데이터) → 인터넷 게이트웨이·NAT → 라우트 테이블 → S3 엔드포인트.
# 서브넷의 종류는 '인터넷으로 가는 길'로 정해진다.
#   퍼블릭: 인터넷 게이트웨이(IGW)로 양방향 통신 (ALB, NAT, Bastion)
#   프라이빗/데이터: NAT를 거쳐 밖으로 나가기만 가능, 밖에서 들어올 수 없음 (앱, Redis, RDS)

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${local.name}-vpc" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${local.name}-igw" }
}

resource "aws_subnet" "public" {
  count = length(var.azs)

  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.public_subnet_cidrs[count.index]
  availability_zone       = var.azs[count.index]
  map_public_ip_on_launch = true

  tags = { Name = "${local.name}-public-${count.index}" }
}

resource "aws_subnet" "private" {
  count = length(var.azs)

  vpc_id            = aws_vpc.main.id
  cidr_block        = var.private_subnet_cidrs[count.index]
  availability_zone = var.azs[count.index]

  tags = { Name = "${local.name}-private-${count.index}" }
}

resource "aws_subnet" "data" {
  count = length(var.azs)

  vpc_id            = aws_vpc.main.id
  cidr_block        = var.data_subnet_cidrs[count.index]
  availability_zone = var.azs[count.index]

  tags = { Name = "${local.name}-data-${count.index}" }
}

# NAT는 비용 때문에 1대만 두고 두 AZ가 공유한다 (2a가 장애면 두 AZ의 아웃바운드가 막히는 단일 장애점)
resource "aws_eip" "nat" {
  domain = "vpc"

  tags = { Name = "${local.name}-nat-eip" }
}

resource "aws_nat_gateway" "main" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[0].id

  tags = { Name = "${local.name}-nat" }

  depends_on = [aws_internet_gateway.main]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name}-public-rt" }
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main.id
  }

  tags = { Name = "${local.name}-private-rt" }
}

# Redis가 Docker 이미지를 받으려면 아웃바운드가 필요해서 NAT를 태운다.
# 라우트 테이블을 분리해 두면 나중에 데이터 서브넷만 외부 통신을 끊기 쉽다
resource "aws_route_table" "data" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main.id
  }

  tags = { Name = "${local.name}-data-rt" }
}

resource "aws_route_table_association" "public" {
  count = length(var.azs)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  count = length(var.azs)

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "data" {
  count = length(var.azs)

  subnet_id      = aws_subnet.data[count.index].id
  route_table_id = aws_route_table.data.id
}

# Gateway 엔드포인트는 무료다. ECR 이미지 레이어가 S3에서 내려오므로 NAT 데이터 처리 비용을 줄인다.
# (Interface 엔드포인트는 시간당 요금이 붙고 NAT도 어차피 필요해서 두지 않는다)
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id, aws_route_table.data.id]

  tags = { Name = "${local.name}-s3-endpoint" }
}