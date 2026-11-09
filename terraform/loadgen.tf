# 부하기(k6): 앱 VPC와 완전히 분리된 별도 VPC의 퍼블릭 서브넷에 둔다.
# ALB의 퍼블릭 도메인으로 요청하므로 실제 사용자와 같은 경로(인터넷 → ALB)로 들어간다. VPC 피어링은 쓰지 않는다.
# 앱 환경과 같은 state라서 destroy하면 같이 지워진다. 쓰지 않을 때는 loadgen_enabled = false로 EC2와 고정 IP를 만들지 않는다.
# 부하기가 병목이면 결과가 의미 없어지므로 CPU가 넉넉한 인스턴스를 쓰고, 부하 중 CloudWatch로 CPU·네트워크를 같이 본다.

resource "aws_vpc" "loadgen" {
  cidr_block           = var.loadgen_vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${local.name}-loadgen-vpc" }
}

resource "aws_internet_gateway" "loadgen" {
  vpc_id = aws_vpc.loadgen.id

  tags = { Name = "${local.name}-loadgen-igw" }
}

resource "aws_subnet" "loadgen" {
  vpc_id                  = aws_vpc.loadgen.id
  cidr_block              = cidrsubnet(var.loadgen_vpc_cidr, 8, 0)
  availability_zone       = var.azs[0]
  map_public_ip_on_launch = true

  tags = { Name = "${local.name}-loadgen-subnet" }
}

resource "aws_route_table" "loadgen" {
  vpc_id = aws_vpc.loadgen.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.loadgen.id
  }

  tags = { Name = "${local.name}-loadgen-rt" }
}

resource "aws_route_table_association" "loadgen" {
  subnet_id      = aws_subnet.loadgen.id
  route_table_id = aws_route_table.loadgen.id
}

resource "aws_security_group" "loadgen" {
  name        = "${local.name}-loadgen-sg"
  description = "k6 load generator"
  vpc_id      = aws_vpc.loadgen.id

  ingress {
    description = "SSH from developers"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = var.allowed_ssh_cidrs
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.name}-loadgen-sg" }
}

resource "aws_instance" "loadgen" {
  count = var.loadgen_enabled ? 1 : 0

  ami                    = data.aws_ssm_parameter.al2023_ami.value
  instance_type          = var.loadgen_instance_type
  subnet_id              = aws_subnet.loadgen.id
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.loadgen.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2["loadgen"].name

  user_data = templatefile("${path.module}/templates/loadgen_user_data.sh.tftpl", {})

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 20
    encrypted   = true
  }

  tags = { Name = "${local.name}-loadgen" }

  depends_on = [aws_route_table_association.loadgen]

  lifecycle {
    ignore_changes = [ami]
  }
}

# 부하 중 재시작해도 접속 주소가 바뀌지 않게 고정 IP를 붙인다
resource "aws_eip" "loadgen" {
  count = var.loadgen_enabled ? 1 : 0

  domain   = "vpc"
  instance = aws_instance.loadgen[0].id

  tags = { Name = "${local.name}-loadgen-eip" }

  depends_on = [aws_internet_gateway.loadgen]
}
