---
title: "재시도를 흩뜨리면 정말 폭주가 줄어들까? RabbitMQ 로컬 실험"
description: "Fixed Retry, Exponential Backoff, Jitter와 PostgreSQL DLQ를 직접 구현하고 같은 실패 입력으로 비교한 개인 프로젝트 기록"
category: "개인 프로젝트"
tags: ["RabbitMQ", "Spring Boot", "Retry", "Backoff", "Jitter", "PostgreSQL", "Testcontainers"]
---

# 재시도를 흩뜨리면 정말 폭주가 줄어들까? RabbitMQ 로컬 실험

여러 소비자가 같은 외부 장애를 동시에 만났다고 가정해 보자. 실패한 작업을 모두 200ms 뒤에 다시 실행하면 구현은 단순하지만, 첫 요청들이 몰렸던 것처럼 재시도도 비슷한 시각에 몰릴 수 있다. 장애로 느려진 대상에 두 번째 파도를 보내는 셈이다.

그렇다면 지수 백오프와 Jitter를 넣으면 항상 더 나아질까? 재시도 시각은 분산될 것 같지만 완료시간도 짧아지는지는 별개의 문제다. 머릿속의 예상만으로 결론을 내리지 않고 직접 확인하기 위해 작은 로컬 실험을 만들었다.

이 글은 회사 시스템의 장애 대응 사례가 아니다. 개인 PC에서 합성 메시지와 합성 실패만 사용한 실험이며, 수치도 이 환경에서 실제 실행한 결과만 기록했다.

## 무엇을 비교했나

비교 대상은 세 가지다.

| 정책 | 첫 번째 재시도 | 두 번째 재시도 | Jitter |
| --- | ---: | ---: | --- |
| Fixed | 200ms | 200ms | 없음 |
| Exponential | 200ms | 400ms | 없음 |
| Exponential + Jitter | 100~300ms | 200~600ms | 기준 지연의 ±50% |

모든 정책은 최초 시도를 포함해 최대 세 번만 실행한다. 세 번을 모두 실패하면 무한 재큐잉하지 않고 PostgreSQL DLQ에 저장한다. 이후 장애가 회복됐다는 조건을 주어 DLQ 메시지를 다시 처리할 수 있게 했다.

프로젝트의 질문은 다음 세 가지였다.

1. Jitter가 짧은 시간 구간의 재시도 집중도를 실제로 낮추는가?
2. 집중도가 낮아졌을 때 메시지 완료시간은 어떻게 달라지는가?
3. 재시도 예산을 소진한 메시지를 잃지 않고 저장하고 다시 처리할 수 있는가?

## 실험용 구조

전체 흐름을 줄이면 다음과 같다.

```text
HTTP 발행
   ↓
RabbitMQ 작업 큐
   ↓
Spring Retry 소비자 ── 성공 ──→ SUCCEEDED
   │
   ├─ 재시도 예산 소진 ──→ PostgreSQL DLQ ──→ 버전 조건부 재처리
   │
   └─ DLQ 저장 실패 ──→ RabbitMQ parking 큐
```

Java 21과 Spring Boot 3.5.16을 사용했고, PostgreSQL 16.9와 RabbitMQ 4.1.4는 Docker Compose로 실행했다. Flyway 계정과 애플리케이션 계정을 분리하고, 애플리케이션 계정에는 필요한 DML 권한만 부여했다. 자동 테스트에서는 Testcontainers로 PostgreSQL과 RabbitMQ를 실제로 띄웠다.

메시지를 발행하면 서버가 UUID와 `publishedAt`을 만들고 durable exchange로 보낸다. 소비자는 `RetryTemplate` 안에서 시도 번호를 증가시키며 합성 실패를 재현한다. 핵심 흐름은 아래와 같다.

```java
retryTemplate.execute(
    context -> {
        int attempt = context.getRetryCount() + 1;
        metrics.attempt(Path.CONSUME, attempt);
        processor.process(message, attempt);
        return Outcome.SUCCEEDED;
    },
    context -> {
        deadLetters.store(message, context.getRetryCount());
        tracker.markFailed(message.messageId());
        return Outcome.FAILED;
    });
```

RabbitMQ 설정에서는 `default-requeue-rejected=false`를 사용했다. 재시도 예산을 모두 쓴 예외를 브로커에 계속 되돌려 무한 루프를 만드는 대신, 애플리케이션이 종료 상태와 저장 위치를 명시적으로 결정한다.

## Exponential Backoff와 Jitter 계산

n번째 재시도의 기준 지연은 아래 규칙으로 계산했다.

```text
baseDelay(n) = min(initialDelay × multiplier^(n-1), maxDelay)
delay(n) = min(maxDelay, baseDelay(n) × (1 - ratio + 2 × ratio × random))
```

`random`은 0 이상 1 미만이고, 이번 실험의 `ratio`는 0.5다. 첫 기준 지연 200ms는 100~300ms, 두 번째 기준 지연 400ms는 200~600ms 범위로 흩어진다. 운영 코드에서는 `ThreadLocalRandom`과 실제 sleep을 사용하고, 테스트에서는 난수원과 sleeper를 주입해 기다리지 않고 경계를 검증했다.

여기서 중요한 차이가 하나 있다. Fixed의 기준 대기 합계는 400ms지만 지수 정책은 600ms다. 따라서 Fixed와 Jitter의 완료시간 차이를 곧바로 “Jitter의 비용”이라고 부를 수 없다. 이 영향을 구분하려고 Jitter가 없는 Exponential 정책을 별도 대조군으로 넣었다.

## 실패를 PostgreSQL DLQ에 남긴 이유

최대 세 번이라는 제한만 두면 재시도 폭주는 막을 수 있지만, 마지막 실패가 사라진다. 그래서 예산을 소진한 메시지는 `retry_lab.dead_letters`에 저장했다.

`message_id`를 기본 키로 두고 `ON CONFLICT DO NOTHING`을 사용해 RabbitMQ 재전달이 발생해도 같은 메시지를 중복 삽입하지 않게 했다. DLQ 저장 트랜잭션이 커밋된 뒤에만 저장 성공 지표를 증가시킨다. 저장 자체가 실패하면 메시지를 작업 큐로 되돌리지 않고 별도의 parking 큐로 보낸다.

재처리는 다음 순서로 동작한다.

1. 클라이언트가 `expectedVersion`을 함께 보낸다.
2. JPA `@Version`으로 한 요청만 `PROCESSING` 상태를 선점한다.
3. 선점 트랜잭션을 끝낸 뒤 실제 처리와 재시도 대기를 수행한다.
4. 성공 또는 실패 상태를 별도 트랜잭션으로 저장한다.

재시도 sleep 동안 DB 트랜잭션을 잡아 두지 않는 대신, 선점 후 프로세스가 종료되면 `PROCESSING`에 남을 수 있다. 자동 lease 복구는 이번 범위에 포함하지 않았다.

또한 이 구조가 exactly-once를 보장하는 것은 아니다. PostgreSQL 커밋과 RabbitMQ ACK는 하나의 원자적 트랜잭션이 아니며, 외부 부수 효과까지 한 번만 실행된다고 말할 수도 없다. 여기서 확인한 것은 DLQ 행의 중복 방지와 동시 재처리 충돌 제어다.

## Prometheus 그래프만으로 부족했던 부분

Micrometer로 처리 시도, 재시도, 종료 결과, 처리시간, DLQ 저장과 충돌을 계측하고 Prometheus로 애플리케이션과 RabbitMQ 지표를 수집했다. 태그에는 메시지 UUID를 넣지 않고 `CONSUME`, `REPLAY`, 정책, 종료 상태처럼 값의 종류가 제한된 항목만 사용했다.

하지만 1초 간격으로 수집한 Prometheus rate를 “정확한 1초 재시도 횟수”로 해석할 수는 없다. scrape 사이의 짧은 피크가 사라질 수 있고 rate 계산 구간도 다르기 때문이다.

그래서 비교 실험에서는 각 메시지의 서버 원본 시각을 사용했다.

- 완료시간: `completedAt - publishedAt`
- 재시도 시각: `attemptTimestamps`에서 첫 시도를 제외
- 재시도 집중도: 원본 시각을 1초와 100ms 반개구간 bucket에 직접 배치
- CPU와 queue depth: Prometheus의 1초 표본
- DB 호출률: `pg_stat_statements`에서 runtime 계정의 successful SQL calls 차이

미완료 메시지, 뒤집힌 시각, 잘못된 시도 횟수, 누락되거나 중복된 DLQ와 재처리 결과가 있으면 분석기는 결과 생성을 거부한다. 생성된 집계는 별도 검증기가 원본 JSON부터 CSV와 블로그 표까지 다시 대조한다.

## 실험 조건

정책별로 세 번씩, 총 아홉 번 실행했다.

- 실행당 워밍업 16건과 측정 96건
- 소비자 8개, prefetch 1
- HTTP 발행 작업자 16개
- 실패 패턴 `[2, 2, 2, 3]`
- 최초 포함 최대 시도 3회
- 실행마다 애플리케이션 재시작
- 반복마다 정책 순서를 순환

실패 패턴 때문에 모든 측정 메시지가 정확히 세 번 시도된다. 네 건 중 세 건은 세 번째에 성공하고 한 건은 최종 실패한다. 따라서 DLQ 비율 25%는 정책이 만든 결과가 아니라 실험 입력으로 고정한 값이다.

Jitter seed는 고정하지 않았다. 실제 운영 난수원과 호스트 스케줄링이 만드는 분산을 관찰하는 대신, 계획·입력·수집기·실행 JAR의 SHA-256과 환경을 manifest에 남겼다. 같은 절차는 재현할 수 있지만 같은 밀리초 값이 다시 나온다고 보장하지는 않는다.

## 실제 결과

아래 값은 각 실행에서 통계를 계산한 다음, 정책별 세 실행의 중앙값을 다시 구한 결과다.

| 정책 | 반복 수 | 완료시간 중앙값(ms) | 완료시간 p95(ms) | 1초 재시도 최대 | 100ms 재시도 최대 |
| --- | ---: | ---: | ---: | ---: | ---: |
| FIXED_200 | 3 | 2856.017 | 5049.251 | 39 | 8 |
| EXPONENTIAL | 3 | 4091.569 | 7435.096 | 31 | 8 |
| EXPONENTIAL_JITTER | 3 | 3957.594 | 7270.813 | 28 | 6 |

Jitter 정책의 1초 재시도 최대는 세 실행에서 29·28·27이었다. Jitter가 없는 지수 대조군은 31·31·32였다. 100ms 최대는 Jitter가 6·6·7, 지수 대조군이 8·8·8이었다. 이번 표본에서는 Jitter를 넣었을 때 짧은 시간 구간에 겹친 재시도 수가 줄었다.

완료시간은 다른 그림을 보여준다. Fixed의 완료시간 p95 중앙값은 5049.251ms, 지수 대조군은 7435.096ms, Jitter는 7270.813ms였다. Jitter가 재시도 시각을 흩뜨렸다는 관찰과 Fixed보다 빨리 끝났다는 주장은 다르다. 지수 정책의 더 긴 기준 대기와 그동안 큐에서 기다린 시간이 완료시간에 함께 반영됐다.

전체 측정 메시지는 864건이었다. 모든 메시지가 세 번씩 시도돼 총 시도 2,592회, 재시도 1,728회가 발생했다. 648건은 성공했고 216건은 DLQ에 저장됐다. 합성 실패 조건을 해제한 재처리도 216건 모두 성공했다. 이 값은 외부 서비스의 실제 장애 복구율이 아니라 DLQ 저장과 재처리 흐름의 기능 검증 결과다.

추가로 관찰한 범위는 다음과 같다.

- queue depth 표본 최대: 82~96
- Java process CPU 비율 표본 최대: 약 0.226~0.299
- runtime SQL calls/s: Fixed 약 7.064~7.221, Exponential 5.315~5.699, Jitter 5.289~5.415

재시도 자체는 DB를 호출하지 않고 최종 실패만 저장한다. 지수 정책의 SQL calls/s가 낮았다는 사실을 DB 효율 개선으로 해석하지 않았다. 더 긴 실행시간으로 분모가 커진 영향도 있기 때문이다.

1초 bucket의 p95는 모든 실행에서 최대값과 같았다. 실행 구간이 짧아 bucket 표본 수가 적었기 때문에 p95라는 이름만으로 분포를 충분히 설명하지 못했다. 100ms bucket과 전체 원본을 함께 남긴 이유다.

## 자동 검증에서 확인한 것

병합된 `main`에서 Java와 Testcontainers 테스트 26건, Python 분석 경계 테스트 9건이 모두 통과했다.

테스트는 다음을 포함한다.

- PostgreSQL Flyway migration과 runtime 계정의 DDL 거부
- RabbitMQ 연결과 메시지 발행·소비
- 즉시 성공, 세 번째 시도 성공, 정확히 세 번 뒤 실패
- 지수 증가, 최대 지연, Jitter 경계와 동시 분산
- DLQ 중복 저장 방지와 저장 실패 parking 큐 이동
- 재처리 성공·실패와 실제 JPA 낙관적 락 충돌
- 지표의 UUID 비노출과 최초 시도 제외 재시도 집계
- 원본 시각, 시도 예산, DLQ 상태가 잘못된 결과의 분석 거부

원본 JSON에서 집계를 다시 계산해 `summary.json`, `retry-buckets.json`, `summary.csv`, 실험 보고서와 이 글의 표가 같은지도 검증했다.

## 해보면서 바뀐 생각

첫째, 재시도 정책의 결과를 평균 완료시간 하나로 판단하면 안 됐다. Jitter는 피크를 낮추기 위한 선택이고, 백오프는 실패한 대상에 시간을 주기 위한 선택이다. 빠른 완료, 부하 분산, 실패 보존은 서로 다른 지표가 필요하다.

둘째, Jitter의 효과를 말하려면 같은 지수 백오프에서 Jitter만 뺀 대조군이 필요했다. Fixed와 Jitter만 비교했다면 총 대기 시간의 차이까지 Jitter 때문이라고 잘못 설명할 수 있었다.

셋째, Prometheus는 시스템 상태를 보는 데 유용하지만 원시 이벤트 기록을 대신하지 않는다. 짧은 재시도 집중도를 알고 싶다면 필요한 정밀도로 이벤트 시각을 수집하거나 그 목적에 맞는 histogram을 설계해야 했다.

마지막으로 DLQ는 실패를 저장하는 장소일 뿐 복구 전략 전체가 아니다. 누가 언제 재처리할지, `PROCESSING`에서 멈춘 항목을 어떻게 회수할지, 외부 부수 효과의 중복을 어떻게 다룰지는 별도의 운영 설계가 필요하다.

## 이 결과가 말하지 못하는 것

이번 실험은 실제 외부 서버를 포화시키거나 네트워크 timeout과 공유 장애의 회복 시점을 재현하지 않았다. 같은 PC에서 세 번씩 실행한 결과로 통계적 유의성이나 운영 환경의 성능을 주장할 수 없다.

DB와 RabbitMQ는 반복 사이에 재사용했기 때문에 캐시와 호스트 상태가 완전히 독립적이지 않다. CPU와 queue depth는 1초 표본이라 더 짧은 피크를 놓칠 수 있다. Jitter 난수, HTTP 스케줄링, RabbitMQ 소비 순서도 실행마다 달라진다.

인증, TLS, publisher confirm, outbox, 분산 트랜잭션, `PROCESSING` lease와 parking 큐 자동 복구도 구현하지 않았다. 합성 데이터만 사용하는 로컬 개인 실험이며 인터넷에 공개 배포할 애플리케이션이 아니다.

## 직접 재현하기

프로젝트와 전체 원본은 GitHub에 공개했다.

- [retry-storm-control-lab 저장소](https://github.com/kiy3035/retry-storm-control-lab)
- [실험 manifest](https://github.com/kiy3035/retry-storm-control-lab/blob/e2527c5/results/stage6-20260904T150000Z/manifest.json)
- [실행별 summary.csv](https://github.com/kiy3035/retry-storm-control-lab/blob/e2527c5/results/stage6-20260904T150000Z/summary.csv)
- [1초·100ms retry bucket 원본](https://github.com/kiy3035/retry-storm-control-lab/blob/e2527c5/results/stage6-20260904T150000Z/retry-buckets.json)
- [정확한 실험 조건과 한계](https://github.com/kiy3035/retry-storm-control-lab/blob/e2527c5/docs/experiment-report.md)

필요한 도구는 Java 21, Docker와 Docker Compose, Python 3.12다. Python 추가 패키지나 유료 서비스는 사용하지 않는다.

```powershell
git clone https://github.com/kiy3035/retry-storm-control-lab.git
cd retry-storm-control-lab

.\gradlew.bat --no-daemon test --rerun-tasks
python -m unittest discover -s scripts -p test_analysis.py -v
python scripts/run-experiment.py
python scripts/analyze-experiment.py results/<출력된-run-id>
python scripts/verify-report.py results/<출력된-run-id>
```

실험 스크립트는 실행마다 임의 비밀번호와 빈 포트, 고유 Compose 프로젝트를 만들고 자신이 생성한 컨테이너·볼륨·네트워크만 정리한다. 기존 결과 폴더를 덮어쓰지 않는다.

## 마무리

이번 결과에서 하나의 정책을 승자로 고르지는 않았다. Fixed는 더 짧은 기준 대기로 빨리 끝났고, Jitter를 넣은 지수 정책은 같은 지수 대조군보다 짧은 구간의 재시도 집중도를 낮췄다. 대신 긴 대기와 운영 복잡도를 감수해야 한다.

재시도 폭주를 다룰 때 중요한 질문은 “Jitter를 넣었는가” 하나가 아니었다. 최대 시도 예산이 있는지, 재시도가 실제로 얼마나 겹치는지, 최종 실패를 어디에 남기는지, 다시 처리할 때 중복과 충돌을 어떻게 막는지를 함께 봐야 했다. 이 프로젝트는 그 질문들을 작은 로컬 환경에서 하나씩 측정 가능한 형태로 바꿔 본 기록이다.
