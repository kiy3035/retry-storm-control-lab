# retry-storm-control-lab 진행 상태

이 문서는 Codex가 대화를 나누어 작업할 때 완료 상태와 다음 시작점을 유지하기 위한 인계 문서다.

측정하거나 실행하지 않은 결과를 추정해서 작성하지 않는다. 확인하지 못한 항목은 `PENDING`으로 둔다.

## 현재 단계

- 현재 단계: 6단계 완료, 사용자 PR 검토 대기
- 상태: COMPLETED
- 마지막 갱신: 2026-09-05 +09:00

## 단계 현황

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 1 | 프로젝트와 로컬 인프라 | COMPLETED |
| 2 | 메시지 발행·소비와 Fixed 재시도 | COMPLETED |
| 3 | Exponential Backoff + Jitter | COMPLETED |
| 4 | JPA DLQ·재처리 API·낙관적 락 | COMPLETED |
| 5 | Micrometer·Prometheus·k6 | COMPLETED |
| 6 | 실제 비교 실험·보고서·블로그 | COMPLETED |

상태값은 다음만 사용한다.

- `NOT_STARTED`
- `IN_PROGRESS`
- `COMPLETED`
- `BLOCKED`
- `PENDING_ENVIRONMENT`

## 완료한 작업

- 6단계: 서버 원본 시각 기반 완료시간과 1초·100ms 재시도 bucket
- 6단계: 동일 입력의 Fixed·Exponential·Jitter 정책별 3회 순환 실행
- 6단계: 864건, 시도 2,592회, 재시도 1,728회의 원본 JSON/CSV
- 6단계: DLQ 저장 216건과 합성 회복 replay 216건 검증
- 6단계: fail-closed 분석기·경계 테스트 9건·원본부터 문서 표까지 대조
- 6단계: 구조·위협 모델·실험 보고서·한국어 블로그
- 5단계: Micrometer Prometheus registry와 처리·재시도·완료 시간·DLQ 지표, 고정 enum 태그
- 5단계: DB 커밋 뒤 INSERTED/DUPLICATE 집계, CONSUME/REPLAY 분리
- 5단계: 선택형 monitoring Compose, RabbitMQ Prometheus plugin과 큐별 depth 수집
- 5단계: 로컬 URL·건수·VU·시간 제한 k6, 종료 상태와 시도 횟수 확인, JSON/CSV 요약
- 5단계: 독립 Compose/JAR/k6/Prometheus 검증 스크립트와 지표 해석 문서
- 4단계: Flyway V3와 JPA DLQ, message_id 기반 동시 중복 저장 방지
- 4단계: 목록·상세·버전 조건부 재처리 API, payload 비노출
- 4단계: 선점/완료 트랜잭션 분리, 실제 JPA 낙관적 락과 HTTP 409 처리
- 4단계: DB 저장 실패의 parking 큐 격리, 재전달별 최대 시도 예산 유지
- 4단계: 고유 Compose 환경에서 실행 JAR 재시작·DLQ 보존·재처리 검증 스크립트
- Java 21, Spring Boot 3.5.16, Gradle 8.12.1 프로젝트와 Wrapper 구성
- PostgreSQL 16.9와 RabbitMQ 4.1.4 Docker Compose 서비스, 볼륨, healthcheck 구성
- Flyway V1 기본 `retry_lab.lab_metadata` 스키마와 deterministic baseline 행 구성
- Flyway V2 migration/runtime 계정 분리 및 runtime DML·sequence 최소 권한 구성
- runtime 계정의 DDL 거부를 포함한 PostgreSQL·RabbitMQ Testcontainers 통합 테스트 구성
- Actuator health endpoint와 DB/RabbitMQ health component 구성
- 값이 비어 있는 `.env.example`, PowerShell 로컬 실행 절차, 결정 기록 구성
- 1단계 변경 전후와 검증 결과 중심의 `docs/pr-stage-1.md` 작성
- durable DirectExchange·Queue·Binding과 Jackson JSON 메시지 변환 구성
- `POST /api/v1/messages` 발행 API와 `GET /api/v1/messages/{messageId}` 상태 조회 API 구성
- 합성 실패 횟수로 즉시 성공, 재시도 후 성공, 예산 소진을 결정적으로 재현
- 최초 포함 최대 3회와 기본 Fixed delay 200ms를 환경 변수로 설정
- 처리 상태, 정확한 시도 횟수, 시도 시각을 메모리에서 추적
- 최종 실패를 `FAILED`로 종료하고 RabbitMQ 무한 재큐잉 방지
- 잘못 복사된 Text2SQL 루트 문서에 RabbitMQ 실험 우선 적용 정정문 추가
- `FIXED`와 `EXPONENTIAL_JITTER` 정책을 환경 설정으로 선택
- 첫 지연, 배수, 최대 지연, Jitter 비율을 검증된 설정값으로 분리
- 지수 기준 지연과 대칭 Jitter, 최종 최대 지연 제한 구현
- 운영용 `ThreadLocalRandom`과 실제 sleeper, 테스트용 난수원·기록 sleeper 분리
- 지수 증가·Jitter 경계·정책 선택·잘못된 표본 거부 단위 테스트 구성
- 8개 스레드에서 100개 동시 첫 재시도 지연이 100개 고유 값으로 분산됨을 실제 sleep 없이 검증

## 실제 실행한 테스트

| 명령 | 결과 | 확인 내용 |
| --- | --- | --- |
| `gradlew.bat --no-daemon clean test` | SUCCESS, 2/2 성공, 최종 실행 1분 5초 | Flyway baseline, runtime 계정 사용, runtime DDL 거부, RabbitMQ 연결, HTTP health |
| `gradlew.bat --no-daemon bootJar` | SUCCESS | 실행 가능한 Spring Boot JAR 생성 |
| 고유 Compose 프로젝트에서 `docker compose up -d --wait` 후 실행 JAR health 확인 | SUCCESS | PostgreSQL/RabbitMQ `healthy`, 애플리케이션과 `db`·`rabbit` 모두 `UP` |
| `gradlew.bat --no-daemon test --rerun-tasks` | SUCCESS, 5/5 성공, 58초 | 1단계 검증 2건과 즉시 성공, 3회째 성공, 정확히 3회 후 실패 |
| 동적 빈 포트의 고유 Compose 프로젝트에서 Boot JAR과 메시지 API 호출 | SUCCESS | health `UP`, 메시지 `SUCCEEDED`, 시도 3회, 간격 209ms·214ms |
| `gradlew.bat --no-daemon test --tests dev.retrystorm.lab.retry.ExponentialJitterBackOffPolicyTest --rerun-tasks` | SUCCESS, 5/5 성공, 16초 | 지수 증가, 최대 지연, Jitter 경계, 정책 선택, 동시 분산 |
| `gradlew.bat --no-daemon test --rerun-tasks` | SUCCESS, 10/10 성공, 34초 | Jitter 단위 테스트 5건과 기존 Testcontainers 통합 테스트 5건 |
| `EXPONENTIAL_JITTER`로 동적 빈 포트 Compose와 Boot JAR 실행 | SUCCESS | health `UP`, 3회째 `SUCCEEDED`, 간격 272ms·533ms |
| 4단계 초기 변경 후 `gradlew.bat --no-daemon test` | SUCCESS, 10/10 성공, 1분 7초 | 기존 테스트 회귀 없음 |
| 4단계 테스트 추가 후 `gradlew.bat --no-daemon test --rerun-tasks` | FAILED, 17건 중 1건 실패, 58초 | 격리 큐 테스트의 수신 타입 변환 문제, 아래 원인 기록 |
| 수신 타입 수정 후 동일 전체 명령 | SUCCESS, 17/17 성공, 58초 | 격리 큐 이동 포함 통과 |
| 4단계 최종 `gradlew.bat --no-daemon test --rerun-tasks` | SUCCESS, 19/19 성공, 49초 | Testcontainers 통합 14건 + Jitter 단위 5건, 실패·오류·skip 0 |
| `scripts/verify-stage4.ps1` | SUCCESS, 내부 bootJar 13초 | 실제 JAR 재시작 전후 DLQ PENDING, 재처리 SUCCEEDED, stale version HTTP 409 |
| 5단계 계측 추가 후 `gradlew.bat --no-daemon test --rerun-tasks` | SUCCESS, 기존 19/19 성공, 1분 36초 | 기존 기능 회귀 검증 |
| 5단계 지표 테스트 추가 후 같은 명령 | FAILED, 24건 중 1건 실패, 2분 37초 | 테스트 환경 Prometheus endpoint 404 |
| 테스트 관측성 활성화 후 같은 명령 | FAILED, 24건 중 1건 실패, 1분 28초 | 테스트용 HTTP 클라이언트 지표에서 URL UUID 감지 |
| 최종 `gradlew.bat --no-daemon test --rerun-tasks` | SUCCESS, 24/24 성공, 1분 50초 | 통합 17건·Jitter 5건·지표 단위 2건, 실패·오류·skip 0, 전체 scrape UUID 비노출 확인 |
| `powershell -NoProfile -File scripts/verify-stage5.ps1` 최종 실행 | SUCCESS, bootJar 20초 | health UP, 수집 대상 2개 UP, k6 12건·check 36/36 통과, 시도 27회·재시도 15회·DLQ 3건·재처리 1건 |
| 네트워크 없는 k6에서 BASE_URL=http://example.com | EXPECTED REJECTION, 종료 코드 107 | HTTP 실행 전 외부 URL 거부 |
| 네트워크 없는 k6에서 ITERATIONS=0 | EXPECTED REJECTION, 종료 코드 107 | 잘못된 부하 예산 거부 |
| 서버 시각 추가 후 `gradlew.bat --no-daemon test --rerun-tasks` | SUCCESS, 26/26 성공, 1분 9초 | 기존 24건 + tracker 시각 2건 |
| `python -m unittest discover -s scripts -p test_analysis.py -v` | SUCCESS, 9/9 성공, 0.001초 | 분위수·bucket·누락·중복·시각·예산·DLQ 상태 경계 |
| `python scripts/run-experiment.py` | SUCCESS, 9/9 실행·정리 완료 | 3정책×3회, 측정 96건과 워밍업 16건 |
| `python scripts/analyze-experiment.py results/stage6-20260904T150000Z` | SUCCESS | JSON/CSV, 1초·100ms bucket, 비교 표 |
| `python scripts/verify-report.py results/stage6-20260904T150000Z` | SUCCESS | 원본→집계→bucket→CSV→보고서·블로그 일치 |
| 6단계 문서·분석기 보강 후 `gradlew.bat --no-daemon test --rerun-tasks` | SUCCESS, 26/26 성공, 47초 | Java·Testcontainers 최종 회귀, 실패·오류·skip 0 |

테스트 개수와 성공·실패 개수를 실제 출력 기준으로 기록한다.

## 실제 인프라 검증

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| PostgreSQL 기동 | SUCCESS | `postgres:16.9-alpine`, server 16.9, Compose `healthy` |
| RabbitMQ 기동 | SUCCESS | `rabbitmq:4.1.4-management-alpine`, server 4.1.4, Compose `healthy` |
| Flyway migration | SUCCESS | `1|create lab schema|true`, `2|create runtime role|true` |
| 애플리케이션 health | SUCCESS | `/actuator/health`: 전체 `UP`, `db=UP`, `rabbit=UP` |
| RabbitMQ 연결 | SUCCESS | Actuator `rabbit=UP`, `rabbitmq-diagnostics -q ping`: `Ping succeeded` |
| Fixed 재시도 실제 흐름 | SUCCESS | 합성 실패 2회 뒤 3회째 성공, 측정 간격 209ms·214ms |
| Exponential Jitter 실제 흐름 | SUCCESS | 첫·두 번째 지수 기준 범위에서 272ms·533ms 뒤 재시도, 3회째 성공 |

검증용 Compose 프로젝트 `retry-storm-control-lab-stage1-check`의 컨테이너, 네트워크, 볼륨은 확인 후 제거했다. 사용자의 기존 Docker 리소스는 변경하지 않았다.

2단계 검증용 Compose 프로젝트 `retry-storm-stage2-check`도 검증 후 컨테이너, 네트워크, 볼륨을 제거했다. 실행 비밀번호는 매번 임의 생성해 프로세스 환경 변수에만 주입했고 파일에 저장하지 않았다.

3단계 검증용 `retry-storm-stage3-check`도 같은 방식으로 실행 후 컨테이너, 네트워크, 볼륨을 제거했다.

4단계 검증용 `retry-storm-stage4-check-e48f9624`의 PostgreSQL·RabbitMQ가 healthy가 된 뒤 실행 JAR을 기동했다. 최종 실패를 저장하고 JAR 프로세스만 종료·재시작해 같은 DB의 DLQ가 보존되는지 확인했다. 실제 출력은 다음과 같다.

```text
HEALTH=UP
DLQ_BEFORE_RESTART=PENDING
DLQ_AFTER_RESTART=PENDING
ORIGINAL_ATTEMPTS=3
REPLAY_STATE=SUCCEEDED
REPLAY_ATTEMPTS=1
REPROCESS_COUNT=1
FINAL_VERSION=2
STALE_VERSION_HTTP=409
```

검증 후 전용 컨테이너·볼륨·네트워크와 앱 프로세스를 정리했다. 비밀번호는 환경 변수로만 주입했다. 4단계 환경 확인 시각은 2026-09-04 20:52:15 +09:00이며 아래와 동일한 OS·CPU·RAM·Docker 환경, Temurin 21.0.8+9 LTS, PostgreSQL 16.9, RabbitMQ 4.1.4를 사용했다. 이 검증은 기능·내구성 확인이며 부하 성능 측정이 아니다.

검증 환경:

- 실행 시각: 2026-09-01 20:56:43 +09:00
- OS: Microsoft Windows 11 Home 64비트, build 26200
- CPU: 11th Gen Intel Core i5-1135G7 @ 2.40GHz
- RAM: 8,379,490,304 bytes
- Java: Eclipse Temurin OpenJDK 21.0.8
- Docker Engine: 24.0.7
- Docker Compose: v2.23.3-desktop.2

5단계 실제 기동 검증은 2026-09-04 21:19:26 +09:00에 같은 로컬 환경에서 수행했다. Prometheus v3.5.0과 k6 1.2.3을 추가했다. 최종 성공 프로젝트 `retry-storm-stage5-check-792ef02f`의 앱·컨테이너·볼륨·네트워크를 정리했다. 앞선 실패 프로젝트 4730d138, 832a82ad와 독립 플러그인 확인 컨테이너도 정리했다. 사용자 기존 Docker 리소스와 중첩 저장소는 변경하지 않았다.

- 실제 결과 요약: `docs/stage5-smoke-result.json`
- 원본 k6 요약과 검증 출력: `build/retry-storm-stage5-check-792ef02f/summary.json`, `summary.csv`, `verification.json` (Git 제외)
- 실행 당시 HEAD는 4단계 `50d447c`이고 5단계 미커밋 변경을 포함했다. 결과 JSON에도 이를 명시했다.
- 소비 종료 후 작업 큐 depth 0, polling timeout 0, HTTP 실패율 0을 확인했다. 실제 다건 전략 비교나 1초 폭주 bucket 측정 결과로 해석하지 않는다.

6단계 실험 `stage6-20260904T150000Z`는 2026-09-05에 완료했다. source `40a5f01`, 실행 시 추적 변경 없음, manifest·cleanup COMPLETED다.

- 총 864건: 성공 648, DLQ 216, 총 시도 2,592, 재시도 1,728
- DLQ 저장 216/216, 합성 장애 해제 replay 216/216
- 완료시간 중앙/p95의 실행별 3회 중앙값(ms): Fixed 2856.017/5049.251, 지수 4091.569/7435.096, Jitter 3957.594/7270.813
- 1초 retry 최대 중앙값: Fixed 39, 지수 31, Jitter 28. 100ms 최대: 8, 8, 6
- queue depth 표본 최대 82~96, process CPU 표본 최대 약 0.226~0.299, runtime SQL calls/s 약 5.289~7.221
- 전용 앱·컨테이너·볼륨·네트워크 정리 완료. 기존 Docker 리소스와 중첩 저장소 미변경
- 환경·hash는 `results/stage6-20260904T150000Z/manifest.json`에 기록

## 현재 정상 동작하는 기능

- `/actuator/prometheus` 지표 노출과 로컬 Prometheus 앱·RabbitMQ 수집
- CONSUME/REPLAY별 처리·재시도·종료 histogram, 커밋 기준 DLQ 저장과 재처리 충돌 집계
- k6 로컬 합성 메시지 발행·최종 상태 검증과 JSON/CSV 요약
- Gradle Wrapper를 이용한 빌드와 테스트
- 환경 변수로 비밀값과 포트를 주입하는 로컬 PostgreSQL·RabbitMQ 실행
- 애플리케이션 시작 시 migration 전용 계정으로 Flyway V1/V2/V3 적용, JPA 스키마 검증
- 제한된 runtime DB 계정으로 애플리케이션 datasource 연결
- Actuator를 통한 PostgreSQL·RabbitMQ 연결 상태 확인
- Testcontainers를 통한 동일 기반의 자동 통합 검증
- HTTP API를 통한 합성 메시지 RabbitMQ 비동기 발행
- durable queue 소비와 제한된 Fixed 재시도
- 성공·최종 실패 상태, 정확한 시도 횟수와 시각 조회
- 최대 시도 횟수와 Fixed delay 환경 변수 설정
- Fixed와 Exponential Jitter 정책 선택
- 지수 기준 지연, Jitter 비율, 최대 지연 환경 변수 설정
- 주입 가능한 난수원과 sleeper를 이용한 결정적 정책 테스트

## 미완료 작업

- 일반 메시지 tracker는 메모리 기반이며 재처리 성공을 반영하지 않음. 재시작 후 최종 실패·재처리 상태는 DB DLQ API에서 확인
- 재처리 선점 후 장애로 PROCESSING에 남는 행의 자동 복구와 parking 자동 소비는 미구현
- DB와 RabbitMQ의 원자적 커밋, 외부 부수 효과 exactly-once, 브로커 동시 장애까지의 DLX 무손실 보장은 하지 않음
- 인증·TLS·outbox·publisher confirm·PROCESSING lease·parking 자동 복구는 프로젝트 범위 밖

## 발생한 오류와 확인된 원인

- 첫 최종 보고서 검증은 외부 실행 승인이 사용량 제한으로 거부됐고, 샌드박스 내부 사용자 Python은 Access Denied였다. 사용자 재개 후 승인된 실행으로 원본 대조와 9/9 테스트를 완료했다. 추가 패키지는 설치하지 않았다.
- 5단계 첫 Compose 기동: RabbitMQ가 exit 1로 종료했고 PostgreSQL·Prometheus는 healthy였다. 정리 전에 로그를 보존하지 못해 최초 종료 원인은 확정하지 못했다. 같은 plugin 파일의 독립 기동과 이후 전체 기동 2회는 성공했다. 재발 시 조사할 수 있도록 실패 로그를 Git 제외 결과 폴더에 보존하도록 보강했다.
- 5단계 두 번째 smoke: k6 12건은 통과했으나 DLQ 건수 확인 실패. PowerShell 5의 `@(Invoke-RestMethod ...)`가 JSON 배열을 한 항목으로 감싼 것이 원인이었다. 직접 변수 대입으로 수정 후 DLQ 3건·재처리·Prometheus 검증까지 성공했다.
- 5단계 첫 24건 테스트: Spring Boot 테스트 기본값이 metrics export를 끄므로 `/actuator/prometheus`가 404였다. 테스트에 `@AutoConfigureObservability`를 추가해 실제 endpoint를 활성화했다.
- 다음 24건 테스트: 테스트용 HTTP 클라이언트의 문자열 조립 URL이 UUID를 client metric 태그에 남겼다. ID가 있는 모든 테스트 요청을 `{id}` URI 템플릿으로 변경했다. 사용자 정의 앱 지표에 UUID를 추가하거나 비노출 검사를 제거하지 않았으며 전체 scrape의 UUID 패턴 거부를 추가했다.
- Windows PowerShell 5에서 새 스크립트의 한글 오류 메시지가 깨졌다. UTF-8 BOM을 추가했고 다음 실행에서 한글 메시지가 정상으로 표시됐다.
- 증상: 4단계 첫 17건 실행에서 parking 수신 테스트 1건 실패
  - 재현 명령: `gradlew.bat --no-daemon test --rerun-tasks`
  - 확인한 원인: 테스트의 범용 `receiveAndConvert`가 RetryMessage 타입 헤더를 기본 trusted packages 밖으로 판단해 거부함. 브로커 격리 큐 이동은 실행됨
  - 수정 내용: 테스트에서 raw 메시지를 수신한 뒤 명시적 RetryMessage DTO로 역직렬화. trusted packages를 확장하지 않음
  - 수정 후 검증: 17/17 성공, 동시 최초 저장과 재전달 예산 검증을 추가한 최종 19/19 성공
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
- 증상: 2단계 첫 실제 기동 명령이 `.gradlew.bat`를 찾지 못해 애플리케이션 실행 전에 중단
  - 확인한 원인: 명령 전달 과정에서 Windows 상대 경로의 역슬래시가 제거됨
  - 수정 내용: `./gradlew.bat`와 슬래시 경로로 변경
  - 수정 후 검증: `bootJar` 성공
- 증상: 고정 포트 `55433`으로 2단계 Compose PostgreSQL 시작 실패
  - 확인한 원인: 다른 로컬 작업이 해당 포트를 사용 중
  - 수정 내용: 기존 프로세스를 종료하지 않고 OS가 배정한 빈 포트 4개를 DB·RabbitMQ·관리 UI·애플리케이션에 사용
  - 수정 후 검증: 두 컨테이너 `healthy`, 실행 JAR health `UP`, 메시지 3회째 성공
- 증상: 루트 지시 문서 3개의 전체 교체가 안전 검토에서 거부됨
  - 확인한 원인: 기존 사용자 지침을 통째로 삭제해 이력을 잃을 위험
  - 수정 내용: 기존 본문을 보존하고 맨 위에 현재 RabbitMQ 실험의 우선 적용 정정문을 추가
  - 수정 후 검증: 세 문서에 현재 범위와 보관 범위가 명시됨
- 증상: 첫 3단계 전체 테스트에서 동시 Jitter 분산 테스트가 100개 중 고유 지연 72개로 임계값 75개를 넘지 못함
  - 확인한 원인: 100~300ms의 정수 구간에서 무작위 표본의 중복을 고려하지 않은 과도한 임계값
  - 첫 수정: 고유 지연 기준을 70개 이상으로 조정하고 양쪽 범위 도달을 추가 검증
- 증상: 다음 실행에서 같은 seed인데 고유 지연이 69개로 바뀌어 다시 실패
  - 확인한 원인: 여러 스레드가 공유한 `Random.nextDouble()`의 내부 난수 추출 조합 순서가 달라져 seed만으로 동시 실행 결과가 결정적이지 않았음
  - 최종 수정: 각 작업에 실행 순서와 무관한 고유 0~1 표본을 주입
  - 수정 후 검증: 100개 지연 모두 100~300ms 범위의 서로 다른 값, 단위 테스트 5/5와 전체 10/10 성공

## PENDING 항목

- 단계 요구사항 중 PENDING 없음
- 운영 환경·통계적 유의성·실제 외부 장애 회복은 프로젝트 범위 밖이며 측정하지 않았다.
- DB 지표는 runtime SQL calls/s이며 PostgreSQL 서버 전체 QPS로 표현하지 않는다.

## 다음 대화에서 시작할 작업

1. 사용자가 6단계 PR을 검토하고 merge
2. 추가 계획 단계 없음. 사용자 요청 없이 운영화 범위를 시작하지 않음
3. 재실험은 새 run-id에 저장하고 기존 결과를 덮어쓰지 않음

## 실행 및 재현 명령

6단계 원본 생성·분석·대조:

```powershell
python scripts/run-experiment.py
python scripts/analyze-experiment.py results/<출력된-run-id>
python scripts/verify-report.py results/<출력된-run-id>
```

5단계 테스트는 아래 전체 테스트 명령을 사용하고, 독립 관측 환경은 다음으로 검증한다.

```powershell
powershell -NoProfile -File scripts/verify-stage5.ps1
```

수동 기동·PromQL·k6 옵션·정리 명령은 `docs/observability.md`에 기록했다.

4단계 전체 테스트와 독립 실제 기동 검증 명령:

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path .gradle-user-home).Path
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + (Resolve-Path .tmp).Path
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
./gradlew.bat --no-daemon test --rerun-tasks
powershell -NoProfile -File scripts/verify-stage4.ps1
```

위 캐시·임시 디렉터리는 이 작업 환경의 Windows 샌드박스용이다. 일반 환경은 README의 `gradlew.bat test`를 사용한다. 검증 스크립트는 필요한 디렉터리를 생성한다.

다음 명령 흐름은 이번 단계에서 실제 성공했다. 비밀번호 변수는 README처럼 현재 셸에서 생성해 주입한다.

```powershell
.\gradlew.bat --no-daemon clean test
.\gradlew.bat --no-daemon bootJar
docker compose up -d --wait
java -jar build\libs\retry-storm-control-lab-0.0.1-SNAPSHOT.jar
Invoke-RestMethod http://localhost:8080/actuator/health
$body = @{ payload = '합성 메시지'; failuresBeforeSuccess = 2 } | ConvertTo-Json
$published = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/messages -ContentType 'application/json' -Body $body
Invoke-RestMethod ("http://localhost:8080/api/v1/messages/" + $published.messageId)
docker compose down
```

3단계 정책을 실행하려면 애플리케이션 시작 전에 다음 값을 설정한다.

```powershell
$env:LAB_RETRY_MODE = 'EXPONENTIAL_JITTER'
$env:LAB_RETRY_INITIAL_DELAY = '200ms'
$env:LAB_RETRY_MULTIPLIER = '2.0'
$env:LAB_RETRY_MAX_DELAY = '5s'
$env:LAB_RETRY_JITTER_RATIO = '0.5'
```

## 5단계 완료 사용자 보고용 요약

- 완료: Micrometer 계측, 로컬 Prometheus와 RabbitMQ 큐 지표, 제한된 k6 검증·JSON/CSV
- 전체 테스트: 24/24 성공, 실패·오류·skip 0, 1분 50초
- 실제 기동: Prometheus 대상 2개 UP, k6 12건 종료·check 36/36, 시도 27회·재시도 15회·DLQ 저장 3건·재처리 성공 1건
- 입력 제한: 외부 URL·ITERATIONS=0 각각 종료 코드 107로 실행 전 거부
- 상태: 5단계 COMPLETED, 4단계 브랜치 기반의 독립 PR 검토 대기, 6단계 미시작
- 한계: 5초 scrape는 1초 bucket 아님, 기능 smoke를 전략 비교 결과로 해석하지 않음, 4단계 장애 복구 한계 유지

## 4단계 완료 당시 사용자 보고용 요약

- 완료: PostgreSQL JPA DLQ, 동시 중복 저장 방지, 버전 조건부 재처리, 저장 실패 격리
- 최종 전체 테스트: 19/19 성공, 실패 0, 오류 0, skip 0, 49초
- 실제 기동: 재시작 전후 PENDING 보존, 최초 3회 실패, 재처리 1회 성공, 최종 version 2, 이전 버전 409
- 상태: 4단계 COMPLETED, PR 검토 대기. 5단계 미시작
- 한계: 일반 tracker는 메모리 기반, PROCESSING 장애 자동 복구와 parking 자동 재처리 미구현, exactly-once 보장 없음
- 검토 본문: `docs/pr-stage-4.md`, 실제 재현: `scripts/verify-stage4.ps1`

## 3단계 완료 당시 사용자 보고용 요약

```text
3단계를 구현하고 검증했습니다.

완료한 작업:
- 선택 가능한 Fixed와 Exponential Jitter 정책
- 지수 증가, Jitter, 최대 지연과 주입 가능한 난수원·sleeper
- 100개 동시 재시도 지연의 결정적 분산 검증

검증 결과:
- Jitter 단위 테스트: 5/5 성공
- 전체 테스트: 10/10 성공, 실패 0, 오류 0
- 실제 Jitter 모드: 3회째 성공, 간격 272ms·533ms

현재 상태:
- 3단계 COMPLETED, 사용자 PR 검토 대기

남은 한계:
- 상태는 메모리 기반이며 실제 다건 부하 비교는 아직 미실행

다음 단계:
- 사용자 확인과 계속 진행 요청 후 4단계 JPA DLQ·재처리·낙관적 락 시작
```

## 2단계 완료 당시 사용자 보고용 요약

```text
2단계를 구현하고 검증했습니다.

완료한 작업:
- RabbitMQ durable topology, 메시지 발행·소비 API, 최대 3회 Fixed 재시도
- 합성 실패 기반의 성공·최종 실패 재현과 상태·시도 시각 조회
- RabbitMQ 프로젝트 범위 정정과 README 재현 절차

검증 결과:
- 전체 테스트: 5/5 성공, 실패 0, 오류 0
- 실제 인프라: PostgreSQL·RabbitMQ healthy, 애플리케이션 health UP
- 실제 메시지: 두 번 실패 후 3회째 성공, 간격 209ms·214ms

현재 상태:
- 2단계 COMPLETED, 사용자 PR 검토 대기

남은 한계:
- 상태는 메모리 기반이며 Jitter와 DB DLQ는 아직 미구현

다음 단계:
- 사용자 확인과 계속 진행 요청 후 3단계 Exponential Backoff + Jitter 시작
```

## 변경한 주요 파일

- `experiments/*`, `compose.experiment.yaml`: 고정 계획과 1초 telemetry 설정
- `scripts/run-experiment.py`, `analyze-experiment.py`, `verify-report.py`: 수집·분석·대조
- `scripts/test_analysis.py`: 분석 경계 9건
- `results/stage6-20260904T150000Z/*`: 9회 원본·환경·hash·CSV/JSON·bucket
- `docs/architecture.md`, `threat-model.md`, `experiment-report.md`, `blog-draft.md`: 최종 문서
- `src/main/java/dev/retrystorm/lab/metrics/LabMetrics.java`: 유한 태그 기반 처리·재시도·DLQ 계측
- `src/test/java/dev/retrystorm/lab/metrics/LabMetricsTest.java`: 최초 시도 제외·태그·시계 오차 검증
- `compose.monitoring.yaml`, `monitoring/*`: 로컬 Prometheus·RabbitMQ plugin과 scrape 설정
- `load/messages.js`: 제한된 k6 실행과 최종 상태 검증
- `scripts/verify-stage5.ps1`: 실제 JAR·k6·Prometheus 검증
- `docs/observability.md`, `docs/stage5-smoke-result.json`: 사용법·한계·실제 기능 검증 결과
- `src/main/java/dev/retrystorm/lab/dlq/*`: JPA 엔티티·저장·재처리·버전 충돌
- `src/main/java/dev/retrystorm/lab/api/DeadLetterController.java`: DLQ 목록·상세·재처리 API
- `src/main/resources/db/migration/V3__create_dead_letters.sql`: DLQ 스키마와 인덱스
- `scripts/verify-stage4.ps1`: 실행 JAR 재시작과 DLQ 내구성·재처리 검증
- `docs/pr-stage-4.md`: 4단계 변경 전후와 실제 검증 결과
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
- `src/main/java/dev/retrystorm/lab/config/*`: RabbitMQ topology와 Fixed 재시도 설정
- `src/main/java/dev/retrystorm/lab/message/*`: 메시지 발행·소비·합성 실패·상태 추적
- `src/main/java/dev/retrystorm/lab/api/*`: 메시지 발행과 상태 조회 API
- `docs/pr-stage-2.md`: 2단계 PR 본문
- `src/main/java/dev/retrystorm/lab/config/RetryMode.java`: 정책 선택 값
- `src/main/java/dev/retrystorm/lab/config/RetryProperties.java`: Fixed/Jitter 설정과 검증
- `src/main/java/dev/retrystorm/lab/retry/*`: Jitter 난수원, 지수 Jitter 정책, 정책 팩토리
- `src/test/java/dev/retrystorm/lab/retry/ExponentialJitterBackOffPolicyTest.java`: 정책·동시 분산 단위 테스트
- `docs/pr-stage-3.md`: 3단계 PR 본문

## 1단계 완료 당시 사용자 보고용 요약

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
