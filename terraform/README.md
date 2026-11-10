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
   aws ssm put-parameter --name /zelo/dev/JWT_SECRET --type SecureString --overwrite --value '<값>'
   ```
   대상: `JWT_SECRET`(Base64 32바이트 이상), `OPENAI_API_KEY`, `GOOGLE_CLIENT_ID/SECRET`, `KAKAO_CLIENT_ID/SECRET`, `PORTONE_API_SECRET`, `PORTONE_STORE_ID`, `PORTONE_WEBHOOK_SECRET`. 포트원을 쓰지 않으면 `PORTONE_MODE`는 `fake` 그대로 둔다. `LLM_MODE`와 소셜 로그인(`SOCIAL_MODE`)도 `fake`로 만들어지며, 운영으로 쓸 때 `real`로 바꾼다(`LLM_MODE=real`이면 OpenAI 호출 비용이 든다).
   파라미터 이름은 `application.yaml`의 `${...}` 이름과 **대소문자까지 같아야** 한다.
2. **이미지 푸시**: `docker buildx build --platform linux/arm64`로 빌드해서 `ecr_repository_url` 출력값으로 푸시 (EC2가 ARM(Graviton)이라 arm64 이미지가 필요하다. Apple Silicon 맥은 기본이 arm64)
3. **배포할 태그 지정**: `aws ssm put-parameter --name /zelo/dev/deploy/image_tag --type String --overwrite --value <태그>`
4. **반영**: 이미 떠 있는 인스턴스는 Bastion이나 Session Manager로 접속해 `sudo /usr/local/bin/zelo-deploy.sh`를 실행한다. 새로 뜨는 인스턴스는 부팅 시 자동으로 같은 스크립트를 실행한다.
5. **헬스체크 전환**: 앱이 정상 기동하고 `health_check_path`가 200을 주는 것을 확인한 뒤 `asg_health_check_type = "ELB"`로 바꿔서 apply 한다.

## 앱 쪽 준비 (Spring Cloud AWS)

- `build.gradle`에 `implementation 'io.awspring.cloud:spring-cloud-aws-starter-parameter-store:4.0.2'`를 추가한다.
- 배포 스크립트가 컨테이너에 `SPRING_CONFIG_IMPORT=aws-parameterstore:/zelo/dev/`, `SPRING_CLOUD_AWS_REGION_STATIC`, `SPRING_PROFILES_ACTIVE`를 환경변수로 넣는다. 서버에서만 SSM을 읽고, 로컬과 CI는 이 값이 없어서 영향이 없다.
- 켤 프로필은 `spring_profile` 변수(기본값 `performance`)로 정한다. 운영은 `prod`로 바꾼다. 배포 스크립트가 launch template 안에 들어 있어서, 프로필을 바꾸면 `terraform apply` 후 인스턴스를 교체해야 반영된다.
- SSM 파라미터는 프로필 파일보다 우선한다. `LLM_MODE`는 기본 `fake`로 만들어지므로 부하 테스트는 그대로 쓰면 되고, 운영으로 쓸 때만 SSM에서 `real`로 바꾼다. `performance`는 스케줄러를 끄고 시드를 켜므로 공유 개발 서버에는 맞지 않는다.
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

## 부하기(k6)

앱 VPC와 분리된 별도 VPC(`loadgen.tf`)의 퍼블릭 서브넷에 EC2와 고정 IP로 만든다. 앱 환경과 같은 state라서 `destroy`하면 같이 지워진다. 쓰지 않을 때는 `loadgen_enabled = false`.

```bash
# 1. 부하기에 테스트 스크립트를 복사한다 (저장소 루트에서)
scp -i <zeloKeyPair.pem> -r load-test ec2-user@$(terraform -chdir=terraform output -raw loadgen_public_ip):~/zelo/

# 2. 접속해서 ALB 주소로 k6를 실행한다
ssh -i <zeloKeyPair.pem> ec2-user@<loadgen_public_ip>
cd ~/zelo && load-test/run-k6.sh load-test/auth/login-smoke.js -e BASE_URL=http://<alb_dns_name>

# 3. 결과를 내려받는다
scp -i <zeloKeyPair.pem> -r ec2-user@<loadgen_public_ip>:~/zelo/docs .
```

- 부하기가 병목이면 결과가 의미 없다. 부하 중 CloudWatch에서 부하기의 CPU와 네트워크를 같이 본다.
- 부팅 때 Docker 설치와 `grafana/k6` 이미지 받기를 하므로 apply 직후 1~2분은 기다린다(`/var/log/zelo-loadgen-bootstrap.log`).

## 프론트 배포 (S3 + CloudFront)

`frontend.tf`가 `https://zelo.wyshin.click` 하나로 화면과 API를 같이 서비스한다.

```
브라우저 ──HTTPS──▶ CloudFront ──▶ S3 (비공개 버킷)   /api/ 로 시작하지 않는 모든 경로
                          └─────▶ ALB (백엔드)        /api/* (캐시 없음, 모든 메서드, 헤더와 쿠키 전달)
```

- 같은 도메인이라 CORS 설정이 필요 없다. 프론트는 API를 `/api/...` 상대 경로로 호출하면 된다.
- 화면 경로를 새로고침해도 404가 나지 않는다. 확장자가 없는 경로(`/products/1` 등)는 CloudFront Function이 `/index.html`로 바꿔서 받는다. 이 함수는 `/api/*`에는 적용되지 않아서 API의 403, 404 응답은 그대로 전달된다.
- CloudFront → ALB 구간은 아직 HTTP다. ALB에 HTTPS를 붙이면 `frontend.tf`의 ALB 오리진을 `https-only`로 바꾼다.
- `terraform destroy`를 하면 버킷의 화면 파일도 같이 지워진다. 다시 apply한 뒤 프론트 저장소의 배포를 한 번 돌려야 한다.

### 프론트 저장소(spa-2/zeloFront)에서 설정할 것

`terraform output`으로 값을 확인해서 프론트 저장소의 Actions 변수(Settings → Secrets and variables → Variables)에 넣는다.

| 변수 | 값 |
|---|---|
| `AWS_ROLE_ARN` | `terraform output -raw frontend_deploy_role_arn` |
| `S3_BUCKET` | `terraform output -raw frontend_bucket` |
| `CLOUDFRONT_DISTRIBUTION_ID` | `terraform output -raw frontend_distribution_id` |

역할은 `dev` 브랜치에서 실행된 Actions만 받을 수 있다(`frontend_deploy_branch`). 다른 브랜치나 PR 실행은 거부된다.

워크플로 예시(`.github/workflows/deploy.yml`). 빌드 결과 폴더가 `dist`가 아니면(`build` 등) 경로를 바꾼다.

```yaml
name: deploy
on:
  push:
    branches: [dev]

permissions:
  id-token: write   # OIDC로 AWS 역할을 받기 위해 필요하다
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: npm
      - run: npm ci
      - run: npm run build

      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ vars.AWS_ROLE_ARN }}
          aws-region: ap-northeast-2

      # 해시가 붙은 정적 파일은 오래 캐시하고, index.html은 항상 새로 받게 한다
      - run: |
          aws s3 sync dist s3://${{ vars.S3_BUCKET }} --delete \
            --exclude index.html --cache-control "public,max-age=31536000,immutable"
          aws s3 cp dist/index.html s3://${{ vars.S3_BUCKET }}/index.html --cache-control "no-cache"
          aws cloudfront create-invalidation \
            --distribution-id ${{ vars.CLOUDFRONT_DISTRIBUTION_ID }} --paths /index.html
```

## 서버 기본 설정 (user-data)

모든 서버(앱, Redis, 수집기, 부하기)는 처음 부팅할 때 시간대를 `Asia/Seoul`로 맞추고 점검 도구(net-tools, nc, telnet, htop, sysstat, lsof, tcpdump, jq 등)를 설치한다. 실패해도 부팅은 계속된다.

- user-data는 **처음 부팅할 때 한 번만** 실행된다. 이미 떠 있는 서버에는 `sudo timedatectl set-timezone Asia/Seoul`과 `sudo dnf install -y net-tools bind-utils nmap-ncat telnet htop sysstat lsof tcpdump jq`를 직접 실행한다.
- 호스트의 시간대는 컨테이너에 이어지지 않는다. 앱(JVM)의 시간대는 이미지(Dockerfile)의 `TZ`로 정한다.
- user-data가 바뀌면 수집기는 서버가 교체되고(Prometheus 데이터 삭제), 앱은 ASG가 인스턴스를 교체하고, Redis와 부하기는 서버가 중지됐다가 다시 시작된다. apply 전에 `terraform plan`으로 확인한다.

## 로그 수집 (Loki)

앱 서버의 컨테이너 로그를 수집기의 Loki로 모아서 Grafana에서 지표와 같이 본다.

```
앱 EC2(각 대) ─ Alloy ─▶ Loki(수집기 EC2, 3100) ◀─ Grafana(Loki 데이터소스)
```

- 앱 서버는 부팅할 때(user-data) Alloy 컨테이너를 띄운다. Docker 소켓에서 `zelo` 컨테이너 로그를 읽어 Loki로 보내며, 서버마다 `instance_id` 라벨이 붙는다. 앱 2대여도 한 곳에서 보고 `instance_id`로 구분한다.
- Loki는 수집기 EC2에서 로그를 로컬 디스크에 7일 보관한다. 3100은 VPC 안쪽에서만 열려 있다.
- 수집기의 사설 IP는 서브넷 안에서 고정(`cidrhost(...10)`)해서, 수집기를 교체해도 앱 설정이 바뀌지 않는다.
- 대시보드 하단에 "ERROR 로그 수/분", "ERROR / WARN 로그", "전체 로그" 패널이 있다. Grafana의 Explore에서 `{job="zelo"}`로 직접 검색할 수도 있다. 서버 하나만 보려면 `{job="zelo", instance_id="i-..."}`.
- 로그에 개인정보를 남기지 않는 규칙(AGENTS.md)은 여기서도 그대로 지켜야 한다. 모은 로그는 Grafana에 접속할 수 있는 사람이 모두 볼 수 있다.
- 수집기나 앱의 user-data가 바뀌면 수집기는 교체되고(Prometheus와 Loki 데이터 삭제), 앱은 ASG가 인스턴스를 교체한다.

## k6 지표를 Grafana로 (Prometheus remote write)

부하기(k6)가 계산한 응답 시간, 실패율을 Prometheus로 보내서 Grafana의 "Zelo k6 부하기 (흐름별)" 대시보드에서 `journey`(흐름), `phase`(warmup, steady), `testid`(실행)로 거른다. 통합 테스트처럼 여러 흐름을 섞어 돌릴 때 어느 흐름이 느려졌는지 서버 지표와 같은 시간축에서 보려는 용도다. 부하기가 있을 때(`loadgen_enabled`)만 켜진다.

```
부하기(k6) ──9090, 부하기 고정 IP만 허용──▶ Prometheus(--web.enable-remote-write-receiver) ──▶ Grafana
```

- Prometheus는 부하기가 있으면 `0.0.0.0:9090`에서 듣고(없으면 `127.0.0.1`), 수집기 보안그룹이 부하기 EIP `/32`에서 오는 9090만 허용한다. 인증이 없으므로 다른 곳에는 열지 않는다. 쓰기와 조회 API가 모두 열린다.
- 사용법은 `load-test/run-k6.sh`의 `PROM_RW_URL`(주소는 `terraform output prometheus_remote_write_url`).
  ```bash
  PROM_RW_URL=http://<수집기 IP>:9090/api/v1/write TESTID=run1 \
    load-test/run-k6.sh load-test/integrated/integrated-load.js -e BASE_URL=http://<ALB 주소>
  ```
- k6의 `url` 태그는 뺀다(`--system-tags`). 주소에 상품 코드, 주문 번호가 들어가서 지표 종류가 수만 개로 불어나 수집기 메모리를 해친다. 요청 구분은 `name` 태그로 한다.
- 처음 쓸 때는 Grafana의 p95를 k6 요약(`--summary-export`)의 p95와 비교한다. 미리 계산해서 보내는 p95가 구간별인지 누적인지 확인이 필요하고, 누적이면 네이티브 히스토그램(`K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true`와 Prometheus `--enable-feature=native-histograms`)으로 바꾼다.
- 합격 판정은 지금처럼 k6 요약이 기준이다. Grafana는 실시간 관찰용이다.
- 수집기의 user-data가 바뀌므로 apply하면 수집기가 교체된다(Prometheus와 Loki 데이터 삭제).

## 환경 분리 (dev = 부하 테스트, prod = 운영)

같은 코드로 환경 두 개를 만든다. 환경마다 값 파일(`envs/*.tfvars`)과 상태 파일(workspace)이 따로다. 리소스 이름과 SSM 경로에 환경 이름이 들어가서(`zelo-prod-*`, `/zelo/prod/...`) 두 환경이 동시에 떠 있어도 부딪히지 않는다.

```bash
# 운영
terraform workspace new prod            # 이미 있으면 terraform workspace select prod
terraform plan  -var-file=envs/prod.tfvars
terraform apply -var-file=envs/prod.tfvars

# 부하 테스트 (끝나면 destroy)
terraform workspace new dev             # 이미 있으면 terraform workspace select dev
terraform apply -var-file=envs/dev.tfvars
```

- **workspace를 반드시 확인하고 apply한다.** `terraform workspace show`가 `prod`인데 `envs/dev.tfvars`로 apply하면 운영이 바뀐다. 현재 상태 파일은 로컬 파일이라 팀원과 공유되지 않는다. 운영 전에 S3 백엔드(상태 파일 보관과 잠금)로 옮기는 것을 권한다.
- 팀원 IP, 키 페어 같은 개인 값은 `terraform.tfvars`(커밋되지 않음)에 둔다. 환경 공통이다.
- **순서: 운영 환경을 먼저 만든다.** 이미지 저장소(ECR)와 SES 도메인 인증은 계정에 하나뿐이라 운영이 만들고(`create_ecr`, `ses_enabled`), 부하 테스트 환경은 만들지 않고 같은 저장소를 읽는다. 운영 환경을 destroy하면 이미지도 같이 지워진다.

| | dev (부하 테스트) | prod (운영) |
|---|---|---|
| 앱 프로필 | `performance` | `prod` |
| 외부 서비스 모드 | fake | 처음 `CHANGE_ME`, 실제 값(real)을 넣어야 앱이 시작 |
| 부하 테스트 시드, fake LLM 파라미터 | 있음 | 없음 |
| 프론트(S3 + CloudFront) | 없음 | 있음 (`zelo.wyshin.click`) |
| 로그 | 수집기의 Loki + Alloy | CloudWatch Logs (보관 30일) |
| Redis | EC2 컨테이너 | ElastiCache (복제본 1, 자동 장애 전환) |
| DB | 단일 AZ | Multi-AZ, 백업 7일, 삭제 보호, 최종 스냅샷 |
| 부하기 | 있음 | 없음 |

### 운영 환경을 처음 만들 때 할 일

1. `terraform apply -var-file=envs/prod.tfvars` (인증서 검증, CloudFront, ElastiCache, Multi-AZ RDS 때문에 20~30분 걸릴 수 있다)
2. SSM 시크릿 입력: `/zelo/prod/` 아래 `JWT_SECRET`(Base64 32바이트 이상), `OPENAI_API_KEY`, 구글/카카오 ID와 시크릿, 포트원 API 시크릿, 스토어 ID, 웹훅 시크릿
3. 모드 입력: `LLM_MODE`, `PORTONE_MODE`, `MAIL_MODE`, `SOCIAL_MODE`를 `real`로. 값을 넣기 전에는 `CHANGE_ME`라서 앱이 시작하지 않는다. SES는 프로덕션 액세스 승인 후에 `MAIL_MODE=real`을 쓴다
4. 이미지 배포: 저장소 변수 `DEPLOY_ENABLED_PROD=true`, `AWS_DEPLOY_ROLE_ARN_PROD`(= `terraform output github_deploy_role_arn`)를 넣고 `main`에 머지하면 CI가 빌드, 푸시, `image_tag` 변경, 서버 교체까지 한다
5. 프론트 저장소 변수(`S3_BUCKET`, `CLOUDFRONT_DISTRIBUTION_ID`, `AWS_ROLE_ARN`)를 운영 값으로 넣고 `main` 배포를 돌린다 (위 "프론트 배포" 참고)
6. 알림: `alarm_email`을 넣고 오는 구독 확인 메일의 링크를 누른다. Grafana 알림은 웹훅 주소를 SSM `/zelo-monitoring/prod/alert_webhook_url`에 넣고 수집기에서 `sudo /usr/local/bin/zelo-alert-contact.sh`를 실행한다
7. 소셜 로그인 리다이렉트 주소와 포트원 웹훅 주소를 운영 도메인(HTTPS)으로 바꾼다

### 아직 안 된 것
- ALB의 HTTPS(`certificate_arn`)와 CloudFront → ALB 구간의 HTTPS. 후자는 ALB용 도메인과 인증서가 필요하다.
- 상태 파일 S3 백엔드, DB 스키마 마이그레이션 도구(운영 프로필도 `ddl-auto: update`).
- 새 장애 전환 구성(RDS Multi-AZ, ElastiCache 복제본)이 실제 장애에서 의도대로 동작하는지 검증.
