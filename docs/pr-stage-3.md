# 3단계: Exponential Backoff + Jitter

## 작업 요약

- 기존 Fixed 정책을 유지하면서 Exponential Jitter를 설정으로 선택할 수 있게 했습니다.
- initial delay, multiplier, max delay, jitter ratio를 외부 설정으로 분리하고 유효성을 검사합니다.
- 지수 기준 지연에 대칭 Jitter를 적용하고 최종 지연을 상한값으로 제한합니다.
- 운영 난수원과 sleeper를 테스트 대역으로 교체할 수 있게 분리했습니다.
- 실제 대기 없이 지수 증가, 경계, 정책 선택, 동시 분산을 검증했습니다.
- 실제 Compose와 Boot JAR을 Jitter 모드로 실행해 메시지 3회째 성공을 확인했습니다.

## 변경 전후

| 구분 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 정책 | Fixed만 지원 | `FIXED`, `EXPONENTIAL_JITTER` 선택 |
| 지연 | 매번 200ms | 200ms 기준, 2배 증가, ±50% Jitter |
| 최대 지연 | 별도 제한 없음 | 기본 5초 상한 |
| 난수 | 없음 | 운영 `ThreadLocalRandom`, 테스트 결정적 표본 |
| 대기 테스트 | 통합 테스트에서 실제 대기 | 기록 sleeper로 즉시 검증 |
| 동시 분산 | 검증 없음 | 8개 스레드·100개 재시도 지연 100개 고유 값 |

## 기본 설정

- `LAB_RETRY_MODE=FIXED`
- `LAB_RETRY_INITIAL_DELAY=200ms`
- `LAB_RETRY_MULTIPLIER=2.0`
- `LAB_RETRY_MAX_DELAY=5s`
- `LAB_RETRY_JITTER_RATIO=0.5`

Exponential Jitter 모드에서 첫 재시도 범위는 100~300ms, 두 번째는 200~600ms입니다. 기존 동작 호환을 위해 기본 모드는 Fixed로 유지했습니다.

## 실제 검증

- Jitter 정책 단위 테스트: 5/5 성공, 16초
- 전체 테스트: 10/10 성공, 실패 0, 오류 0, 34초
- 동시 분산: 100개 첫 재시도 지연이 모두 100~300ms이고 100개 고유 값
- 실제 Compose와 Boot JAR:
  - PostgreSQL·RabbitMQ `healthy`
  - Actuator health `UP`
  - `EXPONENTIAL_JITTER` 모드
  - 메시지 `SUCCEEDED`, 시도 3회
  - 관측 재시도 간격 272ms, 533ms

검증용 `retry-storm-stage3-check` 컨테이너, 네트워크, 볼륨은 완료 후 제거했습니다. 비밀번호는 실행 시 임의 생성해 환경 변수에만 주입했습니다.

## 테스트 수정 이력

공유 `Random`을 동시 호출하는 초기 테스트는 같은 seed에서도 스레드 실행 순서에 따라 고유 지연 수가 72개와 69개로 달라졌습니다. 작업별 결정적 표본으로 변경해 실행 순서와 무관하게 100개 고유 지연을 검증하도록 수정했습니다.

## 현재 한계와 다음 단계

- 메시지 처리 상태는 메모리 기반이라 재시작 시 보존되지 않습니다.
- 실제 다건 부하의 Fixed/Jitter 성능 비교는 관측성과 부하 도구가 준비된 5·6단계 범위입니다.
- 다음 4단계에서 PostgreSQL JPA DLQ, 중복 저장 방지, 재처리 API, 낙관적 락을 구현합니다.
