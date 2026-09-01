# retry-storm-control-lab

RabbitMQ 재시도 전략을 로컬에서 재현하고 비교하기 위한 개인 실험 프로젝트다. 현재는 1단계 인프라와 애플리케이션 기반만 구현되어 있다.

## 비용과 비밀정보 원칙

- 모든 구현과 실험은 무료 로컬 도구만 사용한다.
- 유료 API, 유료 SaaS, 무료 체험, 결제수단 등록이 필요한 서비스는 사용하지 않는다.
- 사용량에 따라 유료 서비스로 전환되는 fallback도 두지 않는다.
- 비밀번호와 토큰은 환경 변수로만 주입하고 Git에 커밋하지 않는다.
- `.env.example`에는 변수 이름만 두고 실제 값을 기록하지 않는다.

## 필요 도구

- Java 21
- Docker와 Docker Compose

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

docker compose up -d --wait
.\gradlew.bat bootRun
```

다른 터미널에서 health endpoint를 확인한다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health | ConvertTo-Json -Depth 5
```

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

테스트는 Flyway baseline, runtime DB 계정, DDL 거부, RabbitMQ 연결, 애플리케이션 health를 확인한다.

## 현재 범위

메시지 발행·소비, 재시도, jitter, DLQ, 부하 측정은 아직 구현하지 않았다. `PROGRESS.md`의 단계 순서에 따라 다음 대화에서 진행한다.
