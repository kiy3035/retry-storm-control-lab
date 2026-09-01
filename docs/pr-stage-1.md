# PR: 1단계 로컬 인프라와 애플리케이션 기반 구성

## 작업 요약

Java 21과 Spring Boot 3.5.16 기반 프로젝트를 만들고, PostgreSQL 16.9와 RabbitMQ 4.1.4를 로컬 Docker Compose에서 실행할 수 있게 구성했다. Flyway migration 계정과 제한된 애플리케이션 계정을 분리했으며, 실제 컨테이너를 사용하는 Testcontainers 통합 테스트와 실행 JAR health 검증을 추가했다.

## 변경 전후

| 항목 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 애플리케이션 | 빌드 파일과 소스 없음 | Java 21/Spring Boot 실행 JAR 생성 가능 |
| PostgreSQL | 실행 환경 없음 | 16.9-alpine Compose 서비스와 healthcheck 구성 |
| RabbitMQ | 실행 환경 없음 | 4.1.4-management-alpine Compose 서비스와 healthcheck 구성 |
| DB migration | 없음 | Flyway schema와 runtime role migration 2개 |
| DB 권한 | 계정 분리 없음 | migration 계정과 DML 전용 runtime 계정 분리, runtime DDL 거부 |
| 자동 검증 | 없음 | PostgreSQL·RabbitMQ Testcontainers 통합 테스트 2건 |
| 운영 확인 | 없음 | Actuator health에서 DB와 RabbitMQ 상태 확인 가능 |
| 비밀값 관리 | 규칙만 존재 | 값 없는 `.env.example`과 실행 시 주입 방식 제공 |
| 비용 통제 | 실행 기준 없음 | 무료 로컬 도구만 허용하고 유료·체험·결제수단 요구 서비스와 유료 fallback 금지 |

## 주요 변경 파일

- `build.gradle`, Gradle Wrapper: Java 21/Spring Boot 빌드와 테스트 의존성
- `compose.yaml`: PostgreSQL, RabbitMQ, 영속 볼륨, healthcheck
- `src/main/resources/application.yml`: DB/Flyway/RabbitMQ/Actuator 설정
- `src/main/resources/db/migration/`: 기본 스키마와 runtime role 권한
- `src/test/java/dev/retrystorm/lab/RetryStormControlLabApplicationTest.java`: 컨테이너 기반 통합 검증
- `README.md`: 로컬 실행과 테스트 재현 절차
- `.gitignore`: 환경 파일, 로컬 설정, 인증서, 개인 키, 자격 증명 제외
- `docs/decisions.md`: 선택 근거, 명세 불일치, 비밀정보·비용 불변 조건 기록
- `PROGRESS.md`: 실제 테스트 및 기동 결과

## 검증 결과

- `gradlew.bat --no-daemon clean test`: 2건 실행, 2건 성공
- 실행 JAR `/actuator/health`: 전체 `UP`, `db=UP`, `rabbit=UP`
- Flyway: V1과 V2 모두 성공
- PostgreSQL: 16.9, Compose health `healthy`
- RabbitMQ: 4.1.4, ping 성공, Compose health `healthy`
- 검증용 Compose 컨테이너·네트워크·볼륨은 확인 후 정리 완료

## 범위 밖

메시지 발행·소비, Fixed 재시도, 지수 백오프와 jitter, DLQ, 부하 측정은 구현하지 않았다. 2단계는 사용자 확인 후에만 시작한다.

## 검토 시 확인할 점

- 저장소의 Text2SQL 명세 문서와 RabbitMQ 재시도 단계표가 서로 다른 프로젝트를 설명한다. 2단계 전에 어떤 명세를 기준으로 유지할지 확정해야 한다.
- 원격 `main`에는 최초 `.gitattributes`만 존재했다. 이 PR이 프로젝트 기반 전체를 처음 추가한다.
