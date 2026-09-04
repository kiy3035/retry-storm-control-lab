# retry-storm-control-lab

RabbitMQ 재시도 전략을 로컬에서 재현하고 비교한 개인 실험 프로젝트다. Fixed와 Exponential Backoff + Jitter, PostgreSQL DLQ 재처리, 로컬 관측성과 부하 도구를 구현하고 6단계 실제 반복 실험과 보고서까지 작성했다.

## 비용과 비밀정보 원칙

- 모든 구현과 실험은 무료 로컬 도구만 사용한다.
- 유료 API, 유료 SaaS, 무료 체험, 결제수단 등록이 필요한 서비스는 사용하지 않는다.
- 사용량에 따라 유료 서비스로 전환되는 fallback도 두지 않는다.
- 비밀번호와 토큰은 환경 변수로만 주입하고 Git에 커밋하지 않는다.
- `.env.example`에는 변수 이름만 두고 실제 값을 기록하지 않는다.

## 필요 도구

- Java 21
- Docker와 Docker Compose
- Python 3.12: 6단계 수집·분석 시 필요, 추가 패키지 없음

## 로컬 실행

비밀번호는 저장소에 저장하지 않는다. PowerShell에서 다음처럼 현재 세션에만 값을 설정한다.

```powershell
$env:POSTGRES_DB = 'retry_storm'
$env:POSTGRES_MIGRATION_USER = 'retry_migrator'
$env:POSTGRES_MIGRATION_PASSWORD = 'local-' + [guid]::NewGuid().ToString('N')
$env:APP_DB_USER = 'retry_app'
$env:APP_DB_PASSWORD = 'local-' + [guid]::NewGuid().ToString('N')
$env:POSTGRES_PORT = '5432'
$env:RABBITMQ_USER = 'retry_app'
$env:RABBITMQ_PASSWORD = 'local-' + [guid]::NewGuid().ToString('N')
$env:RABBITMQ_AMQP_PORT = '5672'
$env:RABBITMQ_MANAGEMENT_PORT = '15672'
$env:SERVER_PORT = '8080'
$env:LAB_RETRY_MAX_ATTEMPTS = '3'
$env:LAB_RETRY_MODE = 'EXPONENTIAL_JITTER'
$env:LAB_RETRY_FIXED_DELAY = '200ms'
$env:LAB_RETRY_INITIAL_DELAY = '200ms'
$env:LAB_RETRY_MULTIPLIER = '2.0'
$env:LAB_RETRY_MAX_DELAY = '5s'
$env:LAB_RETRY_JITTER_RATIO = '0.5'

docker compose up -d --wait
.\gradlew.bat bootRun
```

다른 터미널에서 health endpoint를 확인한다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health | ConvertTo-Json -Depth 5
```

## 메시지 발행과 상태 확인

앞에서 실행한 애플리케이션에 합성 메시지를 발행한다. 다음 예시는 처음 두 번 실패한 뒤 세 번째 시도에 성공한다.

```powershell
$body = @{
    payload = '로컬 합성 메시지'
    failuresBeforeSuccess = 2
} | ConvertTo-Json

$published = Invoke-RestMethod `
    -Method Post `
    -Uri http://localhost:8080/api/v1/messages `
    -ContentType 'application/json' `
    -Body $body

Invoke-RestMethod ("http://localhost:8080/api/v1/messages/" + $published.messageId) |
    ConvertTo-Json -Depth 5
```

처리는 비동기이므로 첫 조회에서 `PENDING` 또는 `PROCESSING`이 보일 수 있다. 종료 후 `SUCCEEDED` 또는 `FAILED`와 `attemptCount`, `attemptTimestamps`를 확인한다.

## 재시도 정책 선택

`LAB_RETRY_MODE`는 `FIXED` 또는 `EXPONENTIAL_JITTER`를 받는다. 기본값은 2단계 기준을 보존하기 위해 `FIXED`다.

Exponential Jitter는 재시도 번호 n에 대해 기준 지연을 `min(initialDelay × multiplier^(n-1), maxDelay)`로 계산하고, 여기에 `1 ± jitterRatio` 범위의 난수를 적용한다. 최종 지연은 `maxDelay`를 넘지 않는다. 기본값이면 첫 재시도는 100~300ms, 두 번째 재시도는 200~600ms 범위다.

테스트에서는 난수원과 sleeper를 주입해 실제 대기 없이 경계와 분산을 검증한다. 운영 실행에서는 스레드 로컬 난수와 실제 sleep을 사용한다.

RabbitMQ 관리 UI는 `http://localhost:15672`에서 확인할 수 있다. 계정은 현재 셸에 설정한 `RABBITMQ_USER`와 `RABBITMQ_PASSWORD`다.

종료할 때 같은 환경 변수 세션에서 다음을 실행한다.

```powershell
docker compose down
```

데이터 볼륨까지 초기화하려는 경우에만 명시적으로 `docker compose down -v`를 사용한다.

## 테스트

통합 테스트는 Testcontainers로 PostgreSQL과 RabbitMQ를 실제로 시작한다.

```powershell
.\gradlew.bat test
```

테스트는 Flyway baseline, runtime DB 계정과 DDL 거부, RabbitMQ 연결, 애플리케이션 health, Fixed 재시도, 지수 증가, Jitter 경계, 정책 선택, 100개 동시 재시도 지연 분산을 확인한다. DLQ 중복 저장 방지, 재처리 성공·실패, 동시 요청과 실제 JPA 낙관적 락 충돌, DB 저장 실패의 격리 큐 이동도 검증한다.

실제 실행 JAR 재시작과 DLQ 내구성까지 한 번에 검증하려면 별도 PowerShell 프로세스에서 다음을 실행한다. 임의 비밀번호와 빈 포트를 사용하며, 종료 시 이 스크립트가 만든 고유 Compose 프로젝트의 컨테이너·볼륨만 제거한다. 기존 로컬 실험 데이터는 건드리지 않는다. 로그는 Git에서 제외한 `build/`에 저장한다.

```powershell
powershell -NoProfile -File scripts/verify-stage4.ps1
```

## DLQ 조회와 재처리

최대 시도 횟수를 소진하면 PostgreSQL `retry_lab.dead_letters`에 먼저 커밋한 뒤 RabbitMQ 처리를 완료한다. `message_id` 기본 키와 충돌 무시 삽입으로 같은 메시지의 중복 저장을 막는다. DLQ 목록·상세 API는 payload 없이 상태와 시도 횟수, 버전만 반환한다.

- `GET /api/v1/dlq?page=0&size=20`: 최신 실패 순 목록, size는 1~100
- `GET /api/v1/dlq/{messageId}`: 상세 조회, 없으면 404
- `POST /api/v1/dlq/{messageId}/reprocess`: 버전 조건부 동기 재처리

`failuresBeforeSuccess=3`으로 메시지를 발행하고 DLQ에 나타난 뒤 실행한다.

```powershell
$id = $published.messageId
$entry = Invoke-RestMethod "http://localhost:8080/api/v1/dlq/$id"
$replay = @{ expectedVersion = $entry.version; failuresBeforeSuccess = 0 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/dlq/$id/reprocess" -ContentType 'application/json' -Body $replay
```

`expectedVersion`은 필수다. `failuresBeforeSuccess`는 로컬 합성 장애의 회복을 재현하는 선택값이며, 생략하면 원래 실패 조건을 유지한다. 재처리는 RabbitMQ에 다시 발행하지 않고 동일한 처리 로직과 선택된 재시도 정책을 호출한다. `PENDING` 또는 `FAILED`만 재처리할 수 있고, 처리 중·완료 상태나 오래된 버전은 409로 거부한다. HTTP 200이어도 재시도 예산을 소진하면 응답의 state는 `FAILED`다. 재시도 대기 중에는 DB 트랜잭션을 유지하지 않는다.

## 4단계 큐 전환과 장애 경계

- 기존 큐의 immutable DLX 인자를 강제로 바꾸지 않도록 새 기본 큐·routing key는 `retry.lab.work.v4`를 사용한다. 기존 `retry.lab.work`는 삭제하지 않는다. 이전 앱으로 기존 큐를 비운 후 새 버전으로 전환한다.
- DB 저장 실패는 `PERSISTENCE_FAILED`로 구분하고 재큐잉 없이 `retry.lab.parking` exchange의 `retry.lab.parking.v4` 격리 큐로 보낸다. 격리 큐 자동 소비·복구는 구현하지 않았다.
- DB 커밋과 RabbitMQ ACK는 분산 트랜잭션이 아니다. 저장 후 재전달의 중복 행은 방지하지만 외부 부수 효과의 exactly-once를 보장하지 않는다. classic durable 큐의 DLX 전달도 브로커 동시 장애까지 무손실을 보장하지 않는다.
- 재처리 선점 후 프로세스가 죽거나 완료 저장에 실패하면 `PROCESSING`에 남을 수 있다. 자동 초기화나 lease 복구는 없으며, 실제 처리 여부를 조사한 뒤 복구해야 한다.
- 일반 메시지 조회(`/messages`)는 메모리 기반이고 재처리 성공을 반영하지 않는다. 재시작 후 보존되는 최종 실패와 재처리 상태는 `/dlq`에서 확인한다.
- payload는 재처리를 위해 DB에 보존하므로 합성 데이터만 사용한다. 인증 없는 개인 로컬 실험이며 공개 배포용이 아니다.

## 현재 범위

1~6단계 구현과 검증을 마쳤다. 운영용 인증·exactly-once·PROCESSING 자동 복구는 구현 범위 밖이며 [안전 경계](docs/threat-model.md)에 한계를 기록했다.

## 로컬 관측성과 부하 도구

[관측성 안내](docs/observability.md)에 지표 정의, PromQL, 로컬 실행 명령과 결과 해석의 한계를 정리했다. 유료 서비스나 계정 등록 없이 Docker의 Prometheus·k6를 실행한다. 독립 기능 검증은 다음 명령으로 수행한다.

```powershell
powershell -NoProfile -File scripts/verify-stage5.ps1
```

12건의 합성 메시지 종료와 DLQ·재처리·Prometheus 수집값을 확인하고 전용 리소스를 정리한다. 이 smoke 검증을 전략별 성능 비교 결과로 사용하지 않는다.

## 실제 반복 실험과 결과

```powershell
python -m unittest discover -s scripts -p test_analysis.py -v
python scripts/run-experiment.py
python scripts/analyze-experiment.py results/<출력된-run-id>
```

수집기는 `experiments/plan.json`의 동일 입력으로 Fixed·지수 대조군·Jitter를 3회씩 실행한다. 기본 앱 동시성은 보존하고 실험 프로세스만 소비자 8개·prefetch 1을 적용한다. 출력 폴더는 매번 새로 만들며 전용 Docker 리소스는 정리한다. 기존 실험·DB·중첩 저장소를 삭제하지 않는다.

완료한 결과를 검증하려면:

```powershell
python scripts/verify-report.py results/stage6-20260904T150000Z
```

- [실측 보고서와 재현 조건](docs/experiment-report.md)
- [한국어 블로그 초안](docs/blog-draft.md)
- [원본 JSON과 환경](results/stage6-20260904T150000Z/manifest.json)
- [실행별 CSV](results/stage6-20260904T150000Z/summary.csv)
- [구현 구조](docs/architecture.md)

이번 표본에서는 Jitter가 지수 대조군보다 짧은 구간 재시도 집중도를 낮췄지만 Fixed보다 완료시간이 짧지는 않았다. 개인 로컬 합성 실험의 관찰이며 일반적인 성능 보장은 아니다.
