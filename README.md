<div align="center">

# Zelo

### 제로클릭(Zero-Click) 커머스

**말하면 장바구니가 차고, `예` 한 번이면 결제까지.**

AI 에이전트가 장보기를 대신합니다

<br>

![Java](https://img.shields.io/badge/Java_21-007396?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![Spring AI](https://img.shields.io/badge/Spring_AI_2.0-6DB33F?style=for-the-badge&logo=spring&logoColor=white)
![OpenAI](https://img.shields.io/badge/OpenAI-412991?style=for-the-badge&logo=openaigym&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL_16-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis_7-DC382D?style=for-the-badge&logo=redis&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white)

[**📖 Wiki**](https://github.com/spa-2/zelo/wiki) · [서비스 시나리오](https://github.com/spa-2/zelo/wiki/서비스-시나리오) · [아키텍처](https://github.com/spa-2/zelo/wiki/아키텍처) · [API 명세](https://github.com/spa-2/zelo/wiki/API-명세)

</div>

<br>

## 🔎 이런 서비스예요

> **사용자 -**  토요일 저녁에 초등학생 둘이랑 넷이 먹을 김치볶음밥 재료, 3만 원 안에서 준비해 줘
>
> **Zelo -**  아이들이 먹기 좋게 맵지 않은 백김치로 골랐어요. 7개 품목, 합계 28,400원이에요. 이대로 주문할까요?
> `[ 예 ]` `[ 아니오 ]`
>
> **사용자 -**  쌀은 집에 있어. 빼 줘
>
> **Zelo -**  쌀을 빼고 다시 담았어요. 6개 품목, 합계 21,900원이에요.

<sub>위 대화의 품목과 금액은 예시입니다.</sub>

메뉴를 고르고, 재료를 정하고, 상품을 비교하고, 장바구니에 담는 일을 AI가 대신합니다. 사용자는 **`예`** 를 한 번 누르면 주문·결제·배송 접수까지 끝납니다.

<br>

## 🤔 무엇이 다른가요

AI가 대신 장을 보면 이런 질문이 생깁니다. **AI가 가격을 틀리면? 두 번 결제되면? AI가 멈추면?**
Zelo는 *AI가 틀리거나 멈춰도 돈과 재고는 안전한 서버*를 목표로 설계했습니다.

| | 원칙 | 어떻게 지키나 | |
|:---:|---|---|:---:|
| 🧾 | **AI는 말하고, 계산은 서버가** | LLM은 "재료 목록(견적 요청)"만 만듭니다. 상품 매칭·가격·재고·합계는 서버가 계산합니다. | [자세히](https://github.com/spa-2/zelo/wiki/견적-생성) |
| ✅ | **`예`를 눌러야만, 결제는 한 번만** | 주문은 AI를 거치지 않는 일반 API로만 진행합니다. 멱등키, DB 유니크 제약, 조건부 상태 변경으로 두 번 눌러도 주문은 1건입니다. | [자세히](https://github.com/spa-2/zelo/wiki/주문-결제-멱등성) |
| 📦 | **동시에 사도 재고는 정확하게** | 재고 선점은 Redisson 분산 락으로 순서를 지켜 처리합니다. | [자세히](https://github.com/spa-2/zelo/wiki/재고-동시성) |
| 🛡️ | **AI가 멈춰도 서비스는 산다** | 시도당 시간 제한, 재시도 1회, 서킷 브레이커를 겁니다. 실패해도 사용자 메시지는 남고, 고정 문구로 답합니다. | [자세히](https://github.com/spa-2/zelo/wiki/LLM-장애-대응) |
| 🚫 | **AI가 사면 안 되는 건 안 산다** | 주류 같은 제한 품목은 견적에서 자동으로 빠집니다. | [자세히](https://github.com/spa-2/zelo/wiki/제한-품목) |

<br>

## 👀 한눈에 보는 흐름

```mermaid
sequenceDiagram
    autonumber
    actor U as 사용자
    participant C as 채팅 (Zelo 서버)
    participant AI as LLM
    participant Q as 견적 (서버)
    participant O as 주문·결제

    U->>C: "김치볶음밥 재료 3만 원 안에서"
    C->>AI: 대화 맥락 + 메시지
    AI-->>C: 견적 요청 (메뉴·인원·재료 목록)
    C->>Q: 상품 매칭 · 가격 계산
    Q-->>C: 견적 (Redis에 10분 보관)
    C-->>U: 견적 카드 + [예] [아니오]
    U->>O: [예] (AI를 거치지 않는 API)
    O->>O: 견적 검증 · 재고 선점 · 결제 · 금액 대조
    O-->>U: 결제 완료 메시지 (채팅방에 저장)
```

> 시나리오 전체(가격 변동, 품절, 결제 실패, LLM 장애 등)는 [Wiki · 서비스 시나리오](https://github.com/spa-2/zelo/wiki/서비스-시나리오)에 있습니다.

<br>

## ⚒️ 기술 스택

| 분류 | 사용 기술 |
|---|---|
| **Language · Framework** | ![Java](https://img.shields.io/badge/Java_21-007396?style=flat-square&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.1-6DB33F?style=flat-square&logo=springboot&logoColor=white) ![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?style=flat-square&logo=springsecurity&logoColor=white) ![JPA](https://img.shields.io/badge/JPA_·_Hibernate-59666C?style=flat-square&logo=hibernate&logoColor=white) |
| **AI** | ![Spring AI](https://img.shields.io/badge/Spring_AI_2.0-6DB33F?style=flat-square&logo=spring&logoColor=white) ![OpenAI](https://img.shields.io/badge/OpenAI_gpt--5--mini-412991?style=flat-square&logo=openaigym&logoColor=white) ![Resilience4j](https://img.shields.io/badge/Resilience4j-1F4E79?style=flat-square) |
| **Data** | ![PostgreSQL](https://img.shields.io/badge/PostgreSQL_16-4169E1?style=flat-square&logo=postgresql&logoColor=white) ![Redis](https://img.shields.io/badge/Redis_7-DC382D?style=flat-square&logo=redis&logoColor=white) ![Redisson](https://img.shields.io/badge/Redisson-C9302C?style=flat-square) |
| **Payment** | ![PortOne](https://img.shields.io/badge/PortOne-0B1EFF?style=flat-square) |
| **Test · Quality** | ![JUnit5](https://img.shields.io/badge/JUnit_5-25A162?style=flat-square&logo=junit5&logoColor=white) ![Mockito](https://img.shields.io/badge/Mockito-78A641?style=flat-square) ![SonarCloud](https://img.shields.io/badge/SonarCloud-F3702A?style=flat-square&logo=sonarqubecloud&logoColor=white) |
| **Build · Infra** | ![Gradle](https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white) ![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white) ![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white) |

<br>

## 👤 작업 파트

| 파트 | 맡은 일 |
|---|---|
| 회원 · 인프라 | 가입 · 로그인 · JWT, 공통 응답 · 에러 코드, Docker, CI/CD |
| 상품 · 견적 | 상품 · 재료 · 카테고리, 상품 매칭, 견적 발급 |
| 재고 · 주문 | 재고 선점 · 동시성, 주문 생성 · 상태 관리 |
| 결제 · 배송 | 포트원 결제, 웹훅, 환불, 배송 |
| AI · 채팅 · 리뷰 | 채팅, LLM 연동 · 장애 대응, 견적 요청, 리뷰 근거 |

<br>

## 🗂️ 문서

| 문서 | 내용 |
|---|---|
| [서비스 시나리오](https://github.com/spa-2/zelo/wiki/서비스-시나리오) | 기본 흐름, 수정 요청, 가격 변동 · 품절, 결제 실패 · 환불, LLM 장애 |
| [아키텍처](https://github.com/spa-2/zelo/wiki/아키텍처) | 도메인 구성, 계층별 책임, 트랜잭션 · 락 경계 |
| [ERD](https://github.com/spa-2/zelo/wiki/ERD) | 테이블 구조와 관계 |
| [API 명세](https://github.com/spa-2/zelo/wiki/API-명세) | 요청 · 응답 · 에러 코드 |
| [견적 생성](https://github.com/spa-2/zelo/wiki/견적-생성) | LLM 견적 요청 → 상품 매칭 → 가격 계산 |
| [주문 · 결제 멱등성](https://github.com/spa-2/zelo/wiki/주문-결제-멱등성) | 두 번 눌러도 한 번만 결제되는 이유 |
| [재고 동시성](https://github.com/spa-2/zelo/wiki/재고-동시성) | 분산 락과 재고 선점 |
| [LLM 장애 대응](https://github.com/spa-2/zelo/wiki/LLM-장애-대응) | 시간 제한 · 재시도 · 서킷 브레이커, 실패 응답 |
| [개발 컨벤션](https://github.com/spa-2/zelo/wiki/개발-컨벤션) | 브랜치 · 커밋 · 코드 규칙 |
| [트러블슈팅](https://github.com/spa-2/zelo/wiki/트러블슈팅) | 개발하며 만난 문제와 해결 |

