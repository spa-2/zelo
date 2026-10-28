# zelo 인프라 (Terraform)

Phase 1: VPC(퍼블릭·프라이빗·데이터 서브넷), NAT 1대, S3 Gateway 엔드포인트, ALB(HTTP), Spring ASG, Redis EC2, RDS Postgres 16, ECR, SSM 파라미터, Bastion.
Phase 2 이후(HTTPS·도메인, S3+CloudFront, Prometheus·Grafana, 배포 자동화)는 아직 없다.

## 사전 준비

1. AWS 자격 증명(`aws configure` 또는 SSO)
2. 콘솔에서 EC2 키 페어를 하나 만든다 (개인키는 Terraform state에 남기지 않으려고 콘솔에서 만든다)
3. `terraform.tfvars.example`을 `terraform.tfvars`로 복사해서 개발자 IP(`/32`)와 키 페어 이름을 채운다

## 실행

```bash
terraform init
terraform plan
terraform apply      # RDS 때문에 10분 안팎 걸린다
```

## apply 이후 순서

1. **시크릿 입력**: `CHANGE_ME`로 만들어진 파라미터에 실제 값을 넣는다. (값은 채팅이나 커밋에 남기지 말 것)
   ```bash
   aws ssm put-parameter --name /zelo/dev/jwt_secret --type SecureString --overwrite --value '<값>'
   ```
   대상: `jwt_secret`(소문자, Base64 32바이트 이상), `OPENAI_API_KEY`, `GOOGLE_CLIENT_ID/SECRET`, `KAKAO_CLIENT_ID/SECRET`, `PORTONE_API_SECRET`, `PORTONE_STORE_ID`, `PORTONE_WEBHOOK_SECRET`. 포트원을 쓰지 않으면 `PORTONE_MODE`는 `fake` 그대로 둔다.
   파라미터 이름은 `application.yaml`의 `${...}` 이름과 **대소문자까지 같아야** 한다.
2. **이미지 푸시**: `docker buildx build --platform linux/arm64`로 빌드해서 `ecr_repository_url` 출력값으로 푸시 (EC2가 ARM(Graviton)이라 arm64 이미지가 필요하다. Apple Silicon 맥은 기본이 arm64)
3. **배포할 태그 지정**: `aws ssm put-parameter --name /zelo/dev/deploy/image_tag --type String --overwrite --value <태그>`
4. **반영**: 이미 떠 있는 인스턴스는 Bastion이나 Session Manager로 접속해 `sudo /usr/local/bin/zelo-deploy.sh`를 실행한다. 새로 뜨는 인스턴스는 부팅 시 자동으로 같은 스크립트를 실행한다.
5. **헬스체크 전환**: 앱이 정상 기동하고 `health_check_path`가 200을 주는 것을 확인한 뒤 `asg_health_check_type = "ELB"`로 바꿔서 apply 한다.

## 앱 쪽 준비 (Spring Cloud AWS)

- `build.gradle`에 `implementation 'io.awspring.cloud:spring-cloud-aws-starter-parameter-store:4.0.2'`를 추가한다.
- 배포 스크립트가 컨테이너에 `SPRING_CONFIG_IMPORT=aws-parameterstore:/zelo/dev/`와 `SPRING_CLOUD_AWS_REGION_STATIC`을 환경변수로 넣어서, 별도 프로파일 파일 없이 서버에서만 SSM을 읽는다. 로컬과 CI는 이 값이 없어서 영향이 없다.
- DB·Redis 주소와 비밀번호는 `spring.datasource.*`, `spring.data.redis.*` 이름의 파라미터가 `application.yaml` 값을 덮어쓴다.
- EC2 역할로 SSM을 읽으므로 인스턴스 메타데이터 hop limit을 2로 두었다(컨테이너에서 자격 증명을 받기 위함).

## 접속

```bash
# Bastion 접속
ssh -i <키 파일> ec2-user@<bastion_public_ip>

# RDS에 SSH 터널 (로컬 15432 → RDS 5432)
ssh -i <키 파일> -N -L 15432:<rds_endpoint>:5432 ec2-user@<bastion_public_ip>
```

Bastion 없이도 `AmazonSSMManagedInstanceCore`가 붙어 있어서 Session Manager로 접속할 수 있다.

## 비용과 정리

- NAT, ALB, RDS, EC2는 켜 둔 시간만큼 과금된다. 쓰지 않을 때는 `terraform destroy`.
- 개발 환경 기본값은 RDS 최종 스냅샷을 만들지 않는다(`db_skip_final_snapshot = true`). destroy하면 DB 데이터가 사라진다.

## 알아둘 한계

- **state에 시크릿이 평문으로 남는다**: DB·Redis 비밀번호를 `random_password`로 생성하므로 `terraform.tfstate`에 들어간다. state와 `*.tfvars`는 커밋하지 않는다(`.gitignore` 처리됨). 여럿이 apply 하려면 암호화된 원격 state가 필요하다.
- **Redis는 단일 EC2**: AOF로 재부팅에는 데이터가 남지만 인스턴스가 교체되면 사라진다. 그러면 모든 사용자가 로그아웃되고 진행 중이던 가입 번호표와 락이 없어진다.
- **NAT 1대**: 2a AZ 장애 시 두 AZ의 아웃바운드(외부 API 호출 포함)가 막힌다.