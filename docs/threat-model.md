# 안전 경계와 남는 한계

## 범위

신뢰된 개인 PC에서 합성 메시지만 사용하는 무료 로컬 실험이다. 회사 운영 시스템, 공개 API, 비밀 데이터 저장소로 사용할 준비가 된 제품이 아니다.

| 위험 | 현재 방어 | 남는 한계 |
| --- | --- | --- |
| 무한 재시도 | 최초 포함 최대 3회 기본 예산, default-requeue-rejected=false | 설정을 사용자가 바꾸면 예산이 달라짐 |
| DLQ 중복 행 | UUID 기본 키와 충돌 무시 INSERT | 외부 부수 효과 exactly-once 아님 |
| 동시 replay | 요청 버전과 JPA @Version, 선점 커밋 후 처리 | PROCESSING 중 프로세스 종료의 자동 복구 없음 |
| DB 저장 실패 | parking 큐 격리와 ERROR 지표 | classic DLX의 브로커 동시 장애 무손실·자동 복구 보장 없음 |
| 비밀정보 노출 | 환경 변수 주입, 빈 .env.example, payload 없는 지표·DLQ 응답 | DB에는 replay용 payload 존재. 로컬 관리자·Docker 권한 보유자는 환경 변수를 볼 수 있음 |
| 지표 cardinality 증가 | enum 태그, URI 템플릿, UUID 비노출 테스트 | RabbitMQ per-object 지표는 큐·연결 수에 비례 |
| 과도한 실험 실행 | 건수·작업자·소비자·시간 상한, 전용 리소스 정리 | 호스트 자원 경합과 짧은 피크는 여전히 발생 가능 |
| 유료 호출·외부 발행 | 무료 로컬 도구, 로컬 URL과 no redirect, cloud 출력 없음 | Docker 이미지·Gradle 의존성 다운로드에는 인터넷 필요 |

## 운영용으로 오해하면 안 되는 부분

- 발행자 confirm/outbox가 없으므로 HTTP 202가 영속 저장 보장이라는 뜻이 아니다.
- DB 커밋과 RabbitMQ ACK는 원자적이지 않다. 커밋 직후 장애의 재전달은 중복 DB 행을 막지만 메시지 처리의 모든 부수 효과를 되돌리거나 단 한 번 실행하는 것은 아니다.
- 메모리 tracker는 자동 만료되지 않고, 재시작하면 사라진다. 장시간 반복 부하에는 별도 보존·상한 설계가 필요하다.
- 인증·권한 검사·TLS·분산 rate limit·외부 시스템 회복 검증은 없다. 앱·DB·RabbitMQ를 인터넷에 공개하면 안 된다. 기존 Compose DB/AMQP 포트는 기본 호스트 바인딩이므로 로컬 방화벽을 유지한다.
- 실험 DB의 pg_stat_statements 설정과 확장은 전용 임시 프로젝트에만 적용한다. migration 계정이 관리자 권한을 갖는 로컬 초기 구성은 운영 최소 권한 설계와 다르다.
- 프로세스 metric와 실제 DB 커밋은 원자적이지 않다. afterCommit 집계는 롤백을 성공으로 세지 않지만 커밋 직후 종료 시 계측 누락은 가능하다.

측정 오차와 일반화 범위는 experiment-report에서 별도로 다룬다.
