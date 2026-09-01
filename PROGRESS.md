# retry-storm-control-lab 진행 상태

이 문서는 Codex가 대화를 나누어 작업할 때 완료 상태와 다음 시작점을 유지하기 위한 인계 문서다.

측정하거나 실행하지 않은 결과를 추정해서 작성하지 않는다. 확인하지 못한 항목은 `PENDING`으로 둔다.

## 현재 단계

- 현재 단계: 1단계 완료, 사용자 검토 대기
- 상태: COMPLETED
- 마지막 갱신: 2026-09-01 21:00:15 +09:00

## 단계 현황

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 1 | 프로젝트와 로컬 인프라 | COMPLETED |
| 2 | 메시지 발행·소비와 Fixed 재시도 | NOT_STARTED |
| 3 | Exponential Backoff + Jitter | NOT_STARTED |
| 4 | JPA DLQ·재처리 API·낙관적 락 | NOT_STARTED |
| 5 | Micrometer·Prometheus·k6 | NOT_STARTED |
| 6 | 실제 비교 실험·보고서·블로그 | NOT_STARTED |

상태값은 다음만 사용한다.

- `NOT_STARTED`
- `IN_PROGRESS`
- `COMPLETED`
- `BLOCKED`
- `PENDING_ENVIRONMENT`

## 완료한 작업

- Java 21, Spring Boot 3.5.16, Gradle 8.12.1 프로젝트와 Wrapper 구성
- PostgreSQL 16.9와 RabbitMQ 4.1.4 Docker Compose 서비스, 볼륨, healthcheck 구성
- Flyway V1 기본 `retry_lab.lab_metadata` 스키마와 deterministic baseline 행 구성
- Flyway V2 migration/runtime 계정 분리 및 runtime DML·sequence 최소 권한 구성
- runtime 계정의 DDL 거부를 포함한 PostgreSQL·RabbitMQ Testcontainers 통합 테스트 구성
- Actuator health endpoint와 DB/RabbitMQ health component 구성
- 값이 비어 있는 `.env.example`, PowerShell 로컬 실행 절차, 결정 기록 구성
- 1단계 변경 전후와 검증 결과 중심의 `docs/pr-stage-1.md` 작성

## 실제 실행한 테스트

| 명령 | 결과 | 확인 내용 |
| --- | --- | --- |
| `gradlew.bat --no-daemon clean test` | SUCCESS, 2/2 성공, 최종 실행 1분 5초 | Flyway baseline, runtime 계정 사용, runtime DDL 거부, RabbitMQ 연결, HTTP health |
| `gradlew.bat --no-daemon bootJar` | SUCCESS | 실행 가능한 Spring Boot JAR 생성 |
| 고유 Compose 프로젝트에서 `docker compose up -d --wait` 후 실행 JAR health 확인 | SUCCESS | PostgreSQL/RabbitMQ `healthy`, 애플리케이션과 `db`·`rabbit` 모두 `UP` |

테스트 개수와 성공·실패 개수를 실제 출력 기준으로 기록한다.

## 실제 인프라 검증

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| PostgreSQL 기동 | SUCCESS | `postgres:16.9-alpine`, server 16.9, Compose `healthy` |
| RabbitMQ 기동 | SUCCESS | `rabbitmq:4.1.4-management-alpine`, server 4.1.4, Compose `healthy` |
| Flyway migration | SUCCESS | `1|create lab schema|true`, `2|create runtime role|true` |
| 애플리케이션 health | SUCCESS | `/actuator/health`: 전체 `UP`, `db=UP`, `rabbit=UP` |
| RabbitMQ 연결 | SUCCESS | Actuator `rabbit=UP`, `rabbitmq-diagnostics -q ping`: `Ping succeeded` |

검증용 Compose 프로젝트 `retry-storm-control-lab-stage1-check`의 컨테이너, 네트워크, 볼륨은 확인 후 제거했다. 사용자의 기존 Docker 리소스는 변경하지 않았다.

검증 환경:

- 실행 시각: 2026-09-01 20:56:43 +09:00
- OS: Microsoft Windows 11 Home 64비트, build 26200
- CPU: 11th Gen Intel Core i5-1135G7 @ 2.40GHz
- RAM: 8,379,490,304 bytes
- Java: Eclipse Temurin OpenJDK 21.0.8
- Docker Engine: 24.0.7
- Docker Compose: v2.23.3-desktop.2

## 현재 정상 동작하는 기능

- Gradle Wrapper를 이용한 빌드와 테스트
- 환경 변수로 비밀값과 포트를 주입하는 로컬 PostgreSQL·RabbitMQ 실행
- 애플리케이션 시작 시 migration 전용 계정으로 Flyway V1/V2 적용
- 제한된 runtime DB 계정으로 애플리케이션 datasource 연결
- Actuator를 통한 PostgreSQL·RabbitMQ 연결 상태 확인
- Testcontainers를 통한 동일 기반의 자동 통합 검증

## 미완료 작업

- Text2SQL 내용의 `AGENTS.md`·`PROJECT_SPEC.md`·`CODEX_PROMPT.md`와 RabbitMQ 재시도 내용의 저장소·`PROGRESS.md` 간 명세 정합성 확정
- 2단계 전체
- 3단계 전체
- 4단계 전체
- 5단계 전체
- 6단계 전체

## 발생한 오류와 확인된 원인

- 증상: 첫 Gradle 실행이 Windows native library를 로드하지 못했고 작업공간 Gradle 홈에서는 Spring Boot 플러그인을 찾지 못했다.
  - 재현 명령: 캐시 설정 없이 로컬 Gradle로 `wrapper` 실행
  - 확인한 원인: 샌드박스 임시 디렉터리 제약과 빈 작업공간 Gradle 캐시
  - 수정 내용: 작업공간 임시 디렉터리와 기존 사용자 Gradle 캐시를 사용해 Wrapper를 생성
  - 수정 후 검증: Wrapper 생성과 후속 `clean test`, `bootJar` 성공
- 증상: 최초 Spring context 테스트 2건 실패
  - 재현 명령: 웹 스타터 없이 `gradlew.bat --no-daemon test`
  - 확인한 원인: HTTP health 테스트의 `TestRestTemplate`이 필요로 하는 Spring Web 클래스 누락
  - 수정 내용: `spring-boot-starter-web` 추가
  - 수정 후 검증: context 초기화 성공
- 증상: RabbitMQ 테스트 연결이 530 `NOT_ALLOWED`로 거부됨
  - 재현 명령: 테스트 사용자만 만들고 `/` vhost 권한 없이 통합 테스트 실행
  - 확인한 원인: Testcontainers RabbitMQ 사용자에 vhost permission 미부여
  - 수정 내용: 테스트 컨테이너에 명시적 `/` vhost configure/write/read 권한 부여
  - 수정 후 검증: RabbitMQ 연결 및 전체 테스트 2건 성공
- 증상: 첫 Compose 검증에서 호스트 포트 `55432` 바인딩 실패
  - 확인한 원인: 기존 로컬 프로세스가 해당 포트를 사용 중
  - 수정 내용: 기존 리소스는 건드리지 않고 확인된 빈 포트 `56432`로 검증
  - 수정 후 검증: PostgreSQL Compose health `healthy`
- 증상: 첫 실행 JAR 검증에서 PostgreSQL `localhost:5432` 연결 거부
  - 확인한 원인: Compose/README의 `POSTGRES_PORT`·`POSTGRES_DB`와 애플리케이션의 `DB_PORT`·`DB_NAME` 불일치
  - 수정 내용: 애플리케이션도 `POSTGRES_HOST`·`POSTGRES_PORT`·`POSTGRES_DB`를 사용하도록 통일
  - 수정 후 검증: 실행 JAR health 전체 `UP`, Flyway V1/V2 성공

## PENDING 항목

- Fixed와 Jitter 실제 부하 결과
- 1초 bucket 최대/p95 재시도 수
- 평균/p95/p99 처리 완료시간
- DLQ 발생률과 저장 성공률
- DLQ 재처리 성공률
- CPU, queue depth, DB QPS

## 다음 대화에서 시작할 작업

1. 사용자가 1단계 PR을 검토하고 merge
2. Text2SQL 명세와 RabbitMQ 재시도 단계표 중 이 저장소에서 유지할 기준 확정
3. 사용자가 `계속 진행해`라고 요청한 경우에만 2단계 메시지 발행·소비와 Fixed 재시도 시작

## 실행 및 재현 명령

다음 명령 흐름은 이번 단계에서 실제 성공했다. 비밀번호 변수는 README처럼 현재 셸에서 생성해 주입한다.

```powershell
.\gradlew.bat --no-daemon clean test
.\gradlew.bat --no-daemon bootJar
docker compose up -d --wait
java -jar build\libs\retry-storm-control-lab-0.0.1-SNAPSHOT.jar
Invoke-RestMethod http://localhost:8080/actuator/health
docker compose down
```

## 변경한 주요 파일

- `build.gradle`, `settings.gradle`, `gradlew*`, `gradle/wrapper/*`: 빌드와 Wrapper
- `compose.yaml`: PostgreSQL·RabbitMQ 서비스, 볼륨, healthcheck
- `.env.example`, `.gitignore`, `.gitattributes`: 로컬 환경과 저장소 기본 규칙
- `src/main/java/dev/retrystorm/lab/RetryStormControlLabApplication.java`: Spring Boot 진입점
- `src/main/resources/application.yml`: DB·Flyway·RabbitMQ·Actuator 설정
- `src/main/resources/db/migration/V1__create_lab_schema.sql`: 기본 스키마와 baseline
- `src/main/resources/db/migration/V2__create_runtime_role.sql`: runtime 계정과 최소 권한
- `src/test/java/dev/retrystorm/lab/RetryStormControlLabApplicationTest.java`: Testcontainers 통합 테스트
- `README.md`: 실행과 재현 절차
- `docs/decisions.md`: 선택 근거, 명세 불일치, 한글 주석·단계별 PR 규칙
- `docs/pr-stage-1.md`: 1단계 PR 본문 초안
- `PROGRESS.md`: 실제 결과와 다음 시작점

## 단계 완료 시 사용자 보고용 요약

```text
1단계를 구현하고 검증했습니다.

완료한 작업:
- Spring Boot 프로젝트, PostgreSQL·RabbitMQ Compose, Flyway 기본 스키마와 계정 분리
- Testcontainers 통합 테스트와 실제 실행 JAR health 검증

검증 결과:
- 전체 테스트: 2/2 성공
- 실제 인프라: PostgreSQL·RabbitMQ healthy, Flyway V1/V2 성공
- 실제 API/메시지 흐름: health endpoint 성공, 메시지 흐름은 2단계 범위라 미구현

현재 상태:
- 1단계 COMPLETED, 사용자 검토·커밋 대기

미완료/PENDING:
- 2단계 이후 및 부하 실험 미시작

다음 단계:
- 사용자 확인과 `계속 진행해` 요청 후 2단계 시작
```
