# Terraform 개념 정리 (이 프로젝트 기준)

Terraform 위한 노트다. 개념마다 이 저장소의 어느 파일에 나오는지 같이 적었다.

## 1. Terraform이 하는 일

- **선언형**이다. "이 서버를 만들어라"가 아니라 "이런 상태여야 한다"를 코드로 적으면, Terraform이 **현재 상태와 비교해서 차이만큼** 만들고 바꾸고 지운다.
- 세 가지를 비교한다: **코드**(원하는 상태), **state**(Terraform이 기억하는 관리 대상 목록), **실제 AWS**.
- `plan`은 변경 계획만 보여 주고, `apply`가 실제로 반영한다. 항상 `plan`부터 읽는다.

## 2. state 파일 (가장 중요)

- `terraform.tfstate`는 "코드의 리소스 ↔ 실제 AWS 리소스 ID" 연결표다. **잃어버리면 Terraform은 기존 리소스를 모르는 상태**가 되어, 같은 걸 또 만들려 하거나 지우지 못한다.
- **시크릿이 평문으로 들어 있다.** 이 프로젝트는 `random_password`로 만든 DB·Redis 비밀번호가 여기에 남는다. 그래서 `terraform/.gitignore`가 `*.tfstate*`를 막는다. 절대 커밋하지 않는다.
- 지금은 **로컬 state**다. 한 사람이 `apply`하는 동안은 괜찮지만, 여럿이 쓰면 서로의 state가 어긋난다. 협업하려면 S3 같은 원격 저장소와 잠금(backend)이 필요하다.
- `.terraform.lock.hcl`(provider 버전 고정)은 **커밋한다.** `.terraform/` 폴더(다운로드된 provider)는 커밋하지 않는다.

## 3. 블록 종류와 이 프로젝트의 예

| 블록 | 뜻 | 예 |
|---|---|---|
| `provider` | 어떤 클라우드와 통신할지 | `provider.tf` (AWS, 리전, 공통 태그) |
| `resource` | **만들 것** | `aws_vpc.main`, `aws_db_instance.main` |
| `data` | **이미 있는 것을 읽기만** | `aws_caller_identity`(내 계정 ID), `aws_ssm_parameter.al2023_ami`(최신 AMI) |
| `variable` | 외부에서 넣는 입력 | `variables.tf` |
| `locals` | 코드 안에서 쓰는 계산값 | `local.name`, `local.ssm_prefix` |
| `output` | apply 후 출력할 값 | `outputs.tf` |

- 참조는 `종류.이름.속성`이다. 예: `aws_vpc.main.id`, `var.region`, `local.name`.
- 다른 리소스의 값은 **apply 전에는 모를 수 있다**(예: RDS 주소). 그 값이 필요한 리소스는 그 리소스가 만들어진 뒤에 만들어진다.

## 4. 의존성과 실행 순서

- **암묵적 의존성**: 다른 리소스를 참조하면 Terraform이 순서를 알아서 정한다. (서브넷은 VPC를 참조하므로 VPC가 먼저)
- **명시적 의존성**(`depends_on`): 참조는 없지만 순서가 중요할 때만 쓴다.
  - `ec2.tf`의 ASG: 부팅 스크립트가 SSM 파라미터를 읽고 NAT를 통해 패키지를 받으므로, 파라미터와 라우트가 먼저 있어야 한다.
  - `ec2.tf`의 Redis: 부팅할 때 비밀번호 파라미터를 읽는다.
- 서로 독립인 리소스는 **병렬로** 만든다. 그래서 로그 순서가 코드 순서와 다르다.
- **순환 참조**는 오류다. 그래서 Redis의 IP를 `cidrhost()`로 미리 정해 두었다. (인스턴스 → 파라미터 → 인스턴스 순환을 피하려고)

## 5. 반복: count와 for_each

| | `count` | `for_each` |
|---|---|---|
| 식별 | **번호**(`[0]`, `[1]`) | **키**(`["app"]`) |
| 쓰는 곳 | 서브넷 2개(`vpc.tf`), ALB 리스너 조건부 생성(`alb.tf`) | IAM 역할(`iam.tf`), SSM 파라미터(`ssm.tf`) |
| 주의 | 중간 항목을 빼면 뒤 번호가 밀려서 **재생성**된다 | 키가 바뀌면 그 항목만 바뀐다 |

- `count = 조건 ? 1 : 0`은 "조건부로 만들기" 관용구다. (`alb.tf`에서 인증서가 있을 때만 HTTPS 리스너)

## 6. lifecycle: 되돌리지 않게 막기

- `ignore_changes`: 코드와 실제 값이 달라져도 **무시**한다.
  - `ssm.tf`의 `CHANGE_ME` 파라미터: 콘솔에서 넣은 실제 시크릿을 다음 apply가 초기값으로 덮어쓰지 않게.
  - `ec2.tf`의 `ami`: 최신 AMI가 나왔다고 **Redis가 교체되어 데이터가 사라지는** 일을 막으려고.
- 반대로 `prevent_destroy`나 `create_before_destroy`도 있다. 이 프로젝트는 쓰지 않는다.

## 7. plan 읽는 법

| 기호 | 뜻 |
|---|---|
| `+` | 새로 만든다 |
| `~` | 제자리에서 수정한다 |
| `-` | 삭제한다 |
| `-/+` | **삭제 후 재생성** (이 줄에 `forces replacement`가 같이 나온다) |

- **`-/+`가 보이면 멈춰서 이유를 읽는다.** DB, Redis, 서브넷, ALB가 재생성 대상이면 데이터 손실이나 서비스 중단이다.
- 이 프로젝트에서 재생성이 위험한 것: `aws_db_instance`(데이터), `aws_instance.redis`(데이터와 세션), 서브넷·보안그룹 이름 변경.
- `apply` 전에 "Plan: N to add, N to change, N to destroy" 요약의 **destroy 개수**를 확인한다.

## 8. 부팅 스크립트(user_data)와 템플릿

- `templatefile()`은 `templates/*.tftpl`에 값을 끼워 넣는다. 셸의 `${변수}`는 Terraform 문법과 겹치므로 `$${변수}`로 **이스케이프**한다.
- **`user_data`는 인스턴스가 처음 부팅할 때 한 번만 실행된다.** 이미 떠 있는 서버의 `user_data`를 바꿔도 다시 실행되지 않는다.
  - 앱 서버(ASG): launch template이 바뀌면 `instance_refresh`가 서버를 새 설정으로 교체한다.
  - Redis(단일 EC2): 바꿔도 반영되지 않는다. 반영하려면 인스턴스를 교체해야 하는데 그러면 데이터가 사라진다.
- 배포는 `zelo-deploy.sh` 하나로 통일했다. 부팅 때와 수동 배포, 이후의 CD가 같은 스크립트를 쓴다.

## 9. 자주 쓰는 명령어

```bash
terraform init        # provider 다운로드, 처음과 provider 버전 변경 시
terraform fmt         # 코드 정렬 (커밋 전에)
terraform validate    # 문법·참조 오류 확인 (AWS 접속 불필요)
terraform plan        # 변경 계획 확인 (AWS 자격 증명 필요)
terraform apply       # 반영. 확인 프롬프트에서 yes
terraform destroy     # 전부 삭제 (DB 데이터도 사라짐)
terraform output      # 출력값 다시 보기
terraform state list  # state가 관리 중인 리소스 목록
```

- 변수는 `terraform.tfvars`, `-var 'key=값'`, 환경변수 `TF_VAR_key` 중 하나로 넣는다.
- **`-target`은 비상용이다.** 일부만 apply하면 state와 코드가 어긋날 수 있다. 단계 구분은 변수와 `count`로 한다.

## 10. 드리프트와 콘솔 수정

- 콘솔에서 직접 바꾼 설정은 다음 `plan`에서 **코드 기준으로 되돌려진다.** 인프라 변경은 코드로 한다.
- 예외는 `ignore_changes`로 명시한 값(시크릿 값, AMI)뿐이다.

## 11. 파일 구성 한눈에 보기

```
provider.tf        어떤 클라우드, 어느 리전, 공통 태그
variables.tf       입력 변수
vpc.tf             네트워크 (VPC, 서브넷, IGW, NAT, 라우트, S3 엔드포인트)
security_groups.tf 방화벽 (누가 어느 포트로)
iam.tf             서버가 AWS API를 쓸 권한
ecr.tf             도커 이미지 저장소
ssm.tf             앱 설정과 시크릿 (SSM Parameter Store), 배포할 이미지 태그
rds.tf             Postgres
ec2.tf             Bastion, Redis, 앱(Launch Template + ASG)
alb.tf             로드밸런서와 헬스체크
outputs.tf         apply 후 출력값
templates/         서버 부팅·배포 스크립트
```

요청 흐름: `인터넷 → ALB → (타겟 그룹) → 앱 EC2(ASG) → Redis EC2 / RDS`
접속 흐름: `개발자 → Bastion → 앱 / Redis / RDS`
설정 흐름: `Terraform → SSM 파라미터 ← 앱(Spring Cloud AWS)이 기동 시 읽음`

## 12. 이 프로젝트에서 꼭 기억할 것

1. **apply 순서**: `apply` → SSM 시크릿 입력 → 이미지 푸시 → `deploy/image_tag` 설정 → 서버에서 배포 스크립트 실행 → 정상 확인 후 `asg_health_check_type = "ELB"`.
2. **과금**: NAT, ALB, RDS, EC2는 켜져 있는 시간만큼 나온다. 안 쓸 때는 `terraform destroy`.
3. **`destroy`하면 DB 데이터가 사라진다**(`db_skip_final_snapshot = true`가 기본).
4. **커밋 금지**: `terraform.tfstate*`, `terraform.tfvars`. 커밋 대상: `.terraform.lock.hcl`.
5. **plan에서 `-/+`와 destroy 개수**를 매번 확인한다.
6. **Redis는 단일 EC2**라서 교체되면 모든 사용자가 로그아웃되고 진행 중인 가입 번호표와 락이 사라진다.
7. **ASG 헬스체크**는 앱이 정상으로 뜨기 전에는 `EC2`로 둔다. `ELB`로 두면 서버가 계속 교체된다.
