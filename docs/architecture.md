# 로컬 재시도 실험 구조

이 저장소는 운영 시스템이 아니라 합성 장애를 재현하는 개인 실험이다. 외부 서비스 호출은 없고 PostgreSQL·RabbitMQ·Prometheus는 로컬 Compose, 검증은 Testcontainers와 로컬 HTTP로 수행한다.

## 발행과 소비

1. MessageController가 payload와 합성 실패 횟수를 검증한다.
2. RetryMessagePublisher가 UUID와 서버 publishedAt을 생성하고 메모리 tracker에 등록한 뒤 durable exchange로 보낸다. SENT 계수는 클라이언트 send 반환이며 publisher confirm이 아니다.
3. RabbitMQ 작업 큐의 리스너가 RetryTemplate로 최대 시도 예산을 적용한다. 매 전달의 시도 번호로 합성 실패를 판정하므로 재전달이 이전 tracker 누계 때문에 성공하지 않는다.
4. 성공하면 SUCCEEDED, 재시도 소진이면 DB DLQ 커밋 뒤 FAILED를 기록한다. 서버 completedAt과 시도별 Instant를 상태 API로 조회한다.
5. DLQ 저장 실패는 PERSISTENCE_FAILED로 표시하고 재큐잉 없이 parking 큐로 보낸다.

기본 소비자 수는 1이다. 비교 수집기는 Spring 설정을 실행 환경에서만 덮어써 소비자 8개·prefetch 1을 적용한다. 애플리케이션 기본값을 성능 실험 값으로 바꾸지 않는다.

## DLQ와 재처리

message_id 기본 키와 ON CONFLICT DO NOTHING이 중복 행을 방지한다. 최초 저장은 기존 행의 state/version/payload를 덮어쓰지 않는다. Flyway만 DDL을 수행하고 JPA는 validate만 수행한다.

재처리는 expectedVersion 확인·PROCESSING 선점 커밋, 트랜잭션 밖의 처리·대기, 완료 커밋 순서다. JPA @Version이 같은 버전을 읽은 동시 요청을 제한한다. 선점 충돌은 HTTP 409이며 실제 처리 시도로 집계하지 않는다. HTTP 200이어도 재시도 소진이면 응답 state는 FAILED다.

DLQ API는 payload를 반환하지 않는다. replay는 RabbitMQ 재발행이 아니라 동일 처리 로직의 동기 호출이다. 합성 실패 횟수 override는 로컬 회복 상황을 만들기 위한 옵션이며 실제 장애 복구를 의미하지 않는다.

## 관측과 측정

- Micrometer: CONSUME/REPLAY, mode, outcome 같은 유한 태그로 처리·재시도·DLQ와 timer를 집계한다.
- Prometheus: 앱과 RabbitMQ metrics endpoint를 수집한다. 일반 관측은 5초, 실험 override는 1초다.
- k6: closed-loop의 제한된 기능 smoke와 최종 상태 확인을 제공한다.
- 비교 수집기: 동일 입력을 16개 발행 작업으로 전송하고 모든 메시지의 서버 원본 시각을 회수한다. 정책 실행 순서를 반복마다 순환한다.
- 분석기: 첫 시도 제외 재시도를 직접 bucket에 배치하고 완료시간·반복별 통계를 계산한다. 미완료 실험, 입력 불일치, 시각 역전·누락은 결과로 승인하지 않는다.

1초 retry bucket은 원본 시각에서 계산하며 Prometheus rate를 변환한 값이 아니다. CPU와 queue depth는 샘플 관측값이다. DB SQL calls는 전용 실험 DB의 pg_stat_statements에서 runtime 계정만 집계하며 SQL 본문은 읽지 않는다.

## 데이터 경계

메모리 tracker는 재시작하면 초기화된다. 영속 DLQ와 tracker는 다른 상태 저장소이며 DLQ replay는 기존 tracker의 FAILED를 바꾸지 않는다. 실험마다 앱을 재시작해 tracker의 누적 메모리 영향을 줄이지만 DB와 브로커 프로세스·호스트 캐시는 공유한다.

비밀번호는 프로세스 환경 변수에만 주입한다. 실행 로그는 Git 제외 build에 두며 결과에는 계정 비밀번호·payload를 쓰지 않는다. 실험 생성 리소스만 finally에서 정리하고 기존 사용자 리소스와 중첩 저장소는 보존한다.
