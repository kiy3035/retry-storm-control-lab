# 2단계: 메시지 발행·소비와 Fixed 재시도

## 작업 요약

- RabbitMQ durable DirectExchange, Queue, Binding을 추가했습니다.
- JSON 메시지 발행자와 비동기 소비자를 연결했습니다.
- 최초 시도를 포함해 최대 3회, 기본 200ms Fixed delay로 제한했습니다.
- 합성 실패 횟수로 즉시 성공, 재시도 후 성공, 최종 실패를 재현했습니다.
- 메시지별 상태, 시도 횟수, 시도 시각을 조회하는 API를 추가했습니다.
- 잘못 복사된 Text2SQL 문서는 기존 이력을 보존하면서 RabbitMQ 실험이 우선 적용되도록 정정했습니다.
- 실행 변수와 API 재현 절차를 README와 `.env.example`에 반영했습니다.

## 변경 전후

| 구분 | 변경 전 | 변경 후 |
| --- | --- | --- |
| RabbitMQ | 연결 health만 확인 | durable topology에 메시지 발행·소비 |
| 재시도 | 미구현 | 최대 3회, Fixed 200ms 기본값 |
| 실패 재현 | 불가능 | `failuresBeforeSuccess`로 결정적 재현 |
| 상태 확인 | health endpoint만 존재 | 메시지 상태·횟수·시각 조회 |
| 최종 실패 | 정의 없음 | 3회 소진 뒤 `FAILED`, 무한 재큐잉 없음 |
| 프로젝트 명세 | Text2SQL 복사본과 충돌 | RabbitMQ 실험 우선 정정문 명시 |

## API

- `POST /api/v1/messages`: 합성 메시지 발행
- `GET /api/v1/messages/{messageId}`: 상태와 시도 내역 조회

## 실제 검증

- `gradlew.bat --no-daemon test --rerun-tasks`
  - 5개 실행
  - 실패 0, 오류 0, 건너뜀 0
  - 즉시 성공 1회, 두 번 실패 뒤 3회째 성공, 계속 실패 시 정확히 3회 종료 확인
- 실제 Compose와 Boot JAR
  - PostgreSQL·RabbitMQ `healthy`
  - Actuator health `UP`
  - 메시지 최종 상태 `SUCCEEDED`
  - 시도 횟수 3회
  - Fixed 재시도 간격 209ms, 214ms

검증용 `retry-storm-stage2-check` 컨테이너, 네트워크, 볼륨은 완료 후 제거했습니다. 비밀번호는 실행 시 임의 생성해 프로세스 환경 변수에만 주입했습니다.

## 현재 한계와 다음 단계

- 상태 저장은 메모리 기반이라 재시작 시 보존되지 않습니다. PostgreSQL DLQ는 4단계에서 구현합니다.
- 현재 정책은 Fixed delay뿐입니다.
- 다음 3단계에서 Exponential Backoff + Jitter, 제어 가능한 난수·대기 정책, Fixed/Jitter 선택 설정, 동시 재시도 분산 테스트를 구현합니다.
