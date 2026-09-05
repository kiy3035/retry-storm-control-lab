---
title: "RabbitMQ 재시도는 흩어졌는데, 더 빨라지진 않았다"
description: "Fixed Retry와 Exponential Backoff + Jitter를 직접 비교하고 PostgreSQL DLQ까지 붙여 본 기록"
category: "개인 프로젝트"
tags: ["RabbitMQ", "Spring Boot", "Retry", "Jitter", "PostgreSQL", "Testcontainers"]
---

# RabbitMQ 재시도는 흩어졌는데, 더 빨라지진 않았다

재시도 코드는 처음엔 단순해 보였다. 실패하면 200ms 기다렸다가 다시 실행하면 된다. 그런데 메시지 하나가 아니라 여러 개가 같은 이유로 동시에 실패하면 이야기가 달라진다. 같은 시간에 실패한 작업들이 같은 시간만큼 기다렸다가 다시 몰려간다. 알람을 전부 같은 시각에 맞춰 놓은 것과 비슷하다.

Jitter를 넣으면 이 요청들을 흩어 놓을 수 있다고 알고는 있었다. 그래도 직접 확인해 보고 싶었다. 얼마나 흩어지는지, 완료시간도 같이 줄어드는지, 마지막까지 실패한 메시지는 어떻게 남길지를 작은 프로젝트로 만들어 측정했다.

회사 시스템 사례는 아니다. 내 PC에서 합성 메시지와 합성 실패만 사용한 개인 실험이다.

## 일단 재시도에 끝을 만들었다

처음 정한 규칙은 간단했다.

- 최초 처리를 포함해 최대 3회
- 기본 Fixed delay는 200ms
- 실패한 메시지를 RabbitMQ에 무한 재큐잉하지 않음
- 3회 모두 실패하면 PostgreSQL DLQ에 저장

흐름은 아래와 같다.

```text
HTTP 발행 → RabbitMQ 작업 큐 → Spring Retry 소비자
                                      ├─ 성공 → SUCCEEDED
                                      ├─ 3회 실패 → PostgreSQL DLQ
                                      └─ DLQ 저장 실패 → parking 큐
```

DLQ의 `message_id`는 기본 키다. 같은 메시지가 다시 전달돼도 `ON CONFLICT DO NOTHING`으로 중복 행을 만들지 않는다. 재처리할 때는 클라이언트가 알고 있는 버전을 같이 보내고, JPA `@Version`으로 먼저 선점한 요청만 처리하게 했다.

이걸로 exactly-once가 되는 건 아니다. PostgreSQL 커밋과 RabbitMQ ACK는 한 트랜잭션이 아니고, 외부 부수 효과의 중복까지 막아 주지도 않는다. 여기서 해결한 범위는 DLQ 중복 저장과 동시 재처리 충돌까지다.

## 비교할 정책은 세 개로 잡았다

Fixed와 Jitter만 비교하면 결과를 잘못 읽기 쉽다. Fixed는 200ms씩 두 번 기다려 총 기준 대기가 400ms다. 이번 지수 백오프는 200ms, 400ms이므로 600ms다. 완료시간이 달라져도 그 차이를 전부 Jitter 탓으로 돌릴 수 없다.

그래서 Jitter가 없는 지수 백오프를 대조군으로 하나 더 넣었다.

| 정책 | 첫 재시도 | 두 번째 재시도 |
| --- | ---: | ---: |
| Fixed | 200ms | 200ms |
| Exponential | 200ms | 400ms |
| Exponential + Jitter | 100~300ms | 200~600ms |

Jitter 비율은 ±50%다. 계산은 다음 정도로 단순하다.

```text
기준 지연 = min(초기 지연 × 배수^(재시도 번호-1), 최대 지연)
실제 지연 = 기준 지연 × (1 ± Jitter 비율)
```

운영 코드에서는 `ThreadLocalRandom`과 실제 sleep을 썼다. 테스트에서는 난수원과 sleeper를 주입해서 기다리지 않고 경계값을 확인했다.

## Prometheus 그래프만 보고 끝낼 뻔했다

Micrometer로 처리·재시도·DLQ 지표를 만들고 Prometheus로 앱과 RabbitMQ를 수집했다. 처음에는 이걸로 재시도 피크도 비교할 생각이었다.

그런데 scrape 주기의 rate는 정확한 1초 재시도 횟수가 아니다. scrape 사이에 짧게 생긴 피크를 놓칠 수 있고, rate 계산 구간도 내가 세고 싶은 구간과 다르다.

결국 메시지마다 서버 시각을 남겼다.

- `publishedAt`: 서버가 메시지를 발행한 시각
- `attemptTimestamps`: 각 처리 시도 시각
- `completedAt`: 성공하거나 DLQ 저장을 마친 시각

완료시간은 `completedAt - publishedAt`으로 계산했다. 재시도 집중도는 첫 시도를 뺀 나머지 시각을 1초와 100ms 구간에 직접 넣어 셌다. Prometheus는 CPU와 queue depth를 보는 용도로 남겼다.

분석기도 대충 넘어가지 않게 했다. 시각 순서가 뒤집혔거나 시도 횟수가 맞지 않거나, 실패 메시지와 DLQ·재처리 결과가 맞지 않으면 집계를 만들지 않는다. 마지막에는 원본 JSON부터 CSV, 보고서, 블로그 표까지 다시 비교한다.

## 실험은 이렇게 돌렸다

- 정책별 3회, 총 9회
- 실행당 워밍업 16건, 측정 96건
- 소비자 8개, prefetch 1
- HTTP 발행 작업자 16개
- 실패 패턴 `[2, 2, 2, 3]`
- 모든 메시지는 최초 포함 최대 3회

실패 패턴 때문에 네 건 중 세 건은 세 번째에 성공하고 한 건은 끝까지 실패한다. 즉, DLQ 비율 25%는 정책이 만들어 낸 성과가 아니라 내가 넣은 실험 조건이다.

순서 효과를 조금이라도 줄이려고 실행할 때마다 정책 순서를 돌렸다. 앱은 매번 재시작했지만 PostgreSQL과 RabbitMQ는 반복 사이에 재사용했다. 그래서 캐시와 호스트 상태까지 완전히 독립적인 실험은 아니다.

## 결과는 예상과 반반 맞았다

아래 값은 실행마다 통계를 낸 뒤, 정책별 세 실행의 중앙값을 다시 구한 결과다.

| 정책 | 반복 수 | 완료시간 중앙값(ms) | 완료시간 p95(ms) | 1초 재시도 최대 | 100ms 재시도 최대 |
| --- | ---: | ---: | ---: | ---: | ---: |
| FIXED_200 | 3 | 2856.017 | 5049.251 | 39 | 8 |
| EXPONENTIAL | 3 | 4091.569 | 7435.096 | 31 | 8 |
| EXPONENTIAL_JITTER | 3 | 3957.594 | 7270.813 | 28 | 6 |

먼저 재시도 집중도는 줄었다. Jitter 정책의 1초 최대값은 세 실행에서 29, 28, 27이었다. Jitter가 없는 지수 대조군은 31, 31, 32였다. 100ms 구간에서는 Jitter가 6, 6, 7이고 지수 대조군은 모두 8이었다.

반면 완료시간은 Fixed가 가장 짧았다. 완료시간 p95의 세 실행 중앙값은 Fixed 5049.251ms, Exponential 7435.096ms, Jitter 7270.813ms였다.

결과를 보고 나니 질문을 나눠야 한다는 게 더 분명해졌다.

- Jitter가 재시도 시각을 흩뜨렸는가? 이번 실행에서는 그랬다.
- Jitter가 Fixed보다 빨리 끝났는가? 그렇지 않았다.
- 그렇다면 Jitter가 느린 원인인가? 그렇게 단정할 수 없다. 지수 정책의 기준 대기부터 더 길다.

전체 측정 메시지는 864건이었다. 모든 메시지가 세 번씩 시도돼 총 시도는 2,592회, 그중 재시도는 1,728회였다. 648건은 성공했고 216건은 PostgreSQL DLQ에 저장됐다. 실패 조건을 0으로 바꿔 재처리한 216건도 모두 성공했다.

이 216/216은 실제 장애 복구율이 아니다. 저장과 재처리 흐름이 내가 만든 합성 조건에서 동작했다는 뜻이다.

CPU와 큐도 같이 보긴 했다. queue depth 표본 최대는 82~96, Java process CPU 비율 표본 최대는 약 0.226~0.299였다. runtime SQL calls/s는 Fixed 7.064~7.221, Exponential 5.315~5.699, Jitter 5.289~5.415였다.

SQL calls/s가 낮다고 DB 효율이 좋아졌다고 해석하지 않았다. 재시도 자체는 DB를 호출하지 않고 마지막 실패만 저장하며, 지수 정책은 실행시간도 더 길기 때문이다.

## 테스트와 원본도 같이 남겼다

병합된 `main`에서 Java·Testcontainers 테스트 26건과 Python 분석 테스트 9건이 통과했다. PostgreSQL 권한, RabbitMQ 발행·소비, 세 번의 시도 예산, Jitter 경계, DLQ 중복 방지, 재처리 충돌, 지표의 UUID 비노출 등을 실제 테스트에 포함했다.

실험 원본에는 비밀번호와 메시지 payload를 넣지 않았다. 계획, 입력, 수집기, 실행 JAR의 SHA-256과 실행 환경을 manifest에 기록했다. Jitter seed는 고정하지 않았기 때문에 같은 절차는 재현해도 같은 밀리초 값까지 반복되지는 않는다.

원본과 코드는 공개 저장소에서 확인할 수 있다.

- [retry-storm-control-lab](https://github.com/kiy3035/retry-storm-control-lab)
- [실험 조건과 환경](https://github.com/kiy3035/retry-storm-control-lab/blob/e2527c5/results/stage6-20260904T150000Z/manifest.json)
- [실행별 결과 CSV](https://github.com/kiy3035/retry-storm-control-lab/blob/e2527c5/results/stage6-20260904T150000Z/summary.csv)
- [1초·100ms 재시도 구간 원본](https://github.com/kiy3035/retry-storm-control-lab/blob/e2527c5/results/stage6-20260904T150000Z/retry-buckets.json)

직접 다시 실행하려면 Java 21, Docker와 Docker Compose, Python 3.12가 필요하다. 유료 서비스나 Python 추가 패키지는 사용하지 않는다.

```powershell
git clone https://github.com/kiy3035/retry-storm-control-lab.git
cd retry-storm-control-lab

.\gradlew.bat --no-daemon test --rerun-tasks
python -m unittest discover -s scripts -p test_analysis.py -v
python scripts/run-experiment.py
python scripts/verify-report.py results/<출력된-run-id>
```

## 남은 찜찜함

세 번씩 돌린 로컬 합성 실험이라 통계적 유의성을 말할 수 없다. 실제 외부 서버의 포화, 네트워크 timeout, 여러 인스턴스가 동시에 겪는 장애도 재현하지 않았다. CPU와 queue depth는 1초 표본이라 더 짧은 피크를 놓칠 수 있다.

운영 기능도 빠져 있다. 인증과 TLS, publisher confirm, outbox, `PROCESSING` 상태의 lease 복구, parking 큐 자동 처리까지는 만들지 않았다. 특히 재처리 선점 뒤 프로세스가 죽으면 해당 행이 `PROCESSING`에 남을 수 있다.

그래도 이번 실험으로 하나는 분명해졌다. 재시도 정책을 고를 때 “Jitter를 넣었는가?”만 물으면 부족하다. 최대 시도 횟수가 있는지, 재시도가 얼마나 겹치는지, 완료시간을 어디서부터 어디까지 잴지, 마지막 실패를 어디에 남길지를 같이 봐야 한다.

Jitter는 이번 실험에서 재시도를 흩뜨렸다. 대신 더 빨리 끝나게 해 주지는 않았다. 그 둘을 같은 말처럼 쓰지 않게 된 것이 이번 프로젝트에서 얻은 가장 큰 결과였다.
