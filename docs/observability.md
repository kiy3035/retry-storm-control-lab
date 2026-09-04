# 로컬 관측성과 부하 도구

5단계는 계측과 재현 도구의 기능 검증이다. Fixed/Jitter 성능 우열, 1초 재시도 폭주 분포, 반복 실험 통계와 블로그 결론은 6단계에서 측정한다.

## 구성과 실행

README의 환경 변수를 설정한 셸에서 실행한다. 앱은 호스트의 8080 포트, Prometheus는 127.0.0.1:9090을 기본으로 쓴다.

```powershell
docker compose -f compose.yaml -f compose.monitoring.yaml up -d --wait
./gradlew.bat bootRun
```

앱이 준비되면 다른 셸에서 지표와 Prometheus 수집 상태를 확인한다.

```powershell
Invoke-WebRequest http://localhost:8080/actuator/prometheus -UseBasicParsing
Invoke-RestMethod http://localhost:9090/api/v1/targets
```

Prometheus UI의 쿼리 화면은 http://localhost:9090 이다. RabbitMQ의 metrics 포트 15692는 호스트에 공개하지 않으며 Compose 내부에서만 수집한다. monitoring override를 사용하지 않으면 RabbitMQ plugin과 Prometheus는 시작하지 않는다.

기본 앱 대상은 `monitoring/targets.json`의 `host.docker.internal:8080`이다. 앱 포트를 바꾸면 별도의 JSON 파일을 만들고 절대 경로를 `PROMETHEUS_TARGETS_FILE`에 지정한다. 파일에 자격 증명을 넣지 않는다. Prometheus UI 포트는 `PROMETHEUS_PORT`로 조정한다.

Docker Desktop 환경을 실제 검증 대상으로 사용했다. Linux의 host-gateway 매핑도 구성했으나 Linux 기동은 검증하지 않았다. 앱은 Docker 컨테이너에서 접근 가능해야 하므로 앱 바인딩을 loopback으로 강제하지 않는다. 로컬 방화벽을 유지하고 외부 네트워크에 공개하지 않는다. 인증·TLS 구성은 이 개인 실험의 범위 밖이다.

## 지표 정의

| Prometheus 이름 | 의미와 경계 |
| --- | --- |
| lab_publish_total | SENT는 클라이언트 send 반환 횟수, ERROR는 send 예외. publisher confirm 또는 실제 소비 성공 수가 아님 |
| lab_processing_attempts_total | 최초 포함 처리 시도 수. path=CONSUME/REPLAY, mode=FIXED/EXPONENTIAL_JITTER |
| lab_retries_total | 각 전달 또는 재처리의 두 번째 이후 실제 시도 수. 최초 시도 제외 |
| lab_processing_duration_seconds | path/outcome별 timer count·sum·histogram. CONSUME는 소비 시작부터 종료까지, REPLAY는 선점 커밋 뒤부터 완료 커밋까지. 재시도 대기 포함 |
| lab_delivery_latency_seconds | 발행 시각부터 소비 종료까지. 큐 대기 포함, 발행자와 소비자의 벽시계 차이에 영향받음. 음수는 0으로 제한 |
| lab_dlq_store_total | INSERTED/DUPLICATE는 DB 커밋 후 집계. ERROR는 소비 복구 경로의 DB 저장 실패 수 |
| lab_dlq_conflicts_total | 재처리 선점 충돌. 실제 재처리 timer/attempt에는 미포함 |
| rabbitmq_queue_messages | RabbitMQ 큐별 전체 메시지 수 |
| rabbitmq_queue_messages_ready | 전달 대기 메시지 수 |
| rabbitmq_queue_messages_unacked | 소비자에게 전달했으나 아직 ACK하지 않은 수 |
| process_cpu_usage / jvm_memory_used_bytes | Micrometer 기본 JVM 관측값. Docker 전체 CPU나 DB QPS가 아님 |

애플리케이션 사용자 정의 태그는 유한한 enum 값만 사용한다. messageId, payload, 비밀번호, 예외 원문은 태그에 넣지 않는다. 지표는 프로세스 재시작 시 초기화되는 누적값이므로 영속 DLQ 행 수와 같다고 해석하지 않는다. 성공 재처리 뒤에도 DB 행은 보존되며 INSERTED 누계는 줄지 않는다.

오류는 처리 중 예외 또는 저장 실패, FAILED는 재시도 예산 소진이다. 성공·실패 timer count로 종료 건수를 계산한다. DB와 metric 변경은 원자적이지 않으므로 커밋 직후 프로세스가 종료되면 집계가 누락될 수 있다.

## PromQL 예시

```promql
sum by (path, mode) (rate(lab_retries_total[1m]))
sum by (outcome) (increase(lab_processing_duration_seconds_count{path="CONSUME"}[5m]))
histogram_quantile(0.95, sum by (le) (rate(lab_processing_duration_seconds_bucket{path="CONSUME"}[5m])))
sum by (outcome) (increase(lab_dlq_store_total[5m]))
sum(rabbitmq_queue_messages{queue="retry.lab.work.v4"})
sum(rabbitmq_queue_messages_ready{queue="retry.lab.work.v4"})
sum(rabbitmq_queue_messages_unacked{queue="retry.lab.work.v4"})
sum(rabbitmq_queue_messages{queue="retry.lab.parking.v4"})
```

히스토그램 p95는 버킷에 의한 추정치이며 샘플이 없는 구간은 NaN일 수 있다. 5초 scrape의 rate/increase는 1초 실제 재시도 bucket이나 정확한 이벤트 개수를 대체하지 못한다. 이번 단계는 해당 비교 결과를 기록하지 않는다.

## k6 로컬 검증

앱 기동과 동일한 Compose 환경 변수를 가진 셸에서 실행한다.

```powershell
New-Item -ItemType Directory -Force build/load-results | Out-Null
docker compose -f compose.yaml -f compose.monitoring.yaml run --rm k6
```

기본값은 VU 2개, 총 12건, 최대 실행 2분이다. 각 메시지의 합성 실패 수는 전역 iteration 번호를 4로 나눈 나머지(0·1·2·3)로 결정한다. 최초 포함 최대 3회인 앱 설정을 전제로 최종 상태와 시도 횟수를 확인한다. 단순 HTTP 202를 처리 성공으로 간주하지 않는다. 각 VU가 종료까지 조회하므로 이 도구는 closed-loop 부하다. 독립적인 고정 도착률이나 포화 상태를 재현하는 도구는 아니다.

| 환경 변수 | 기본값 / 범위 |
| --- | --- |
| LOAD_BASE_URL | http://host.docker.internal:8080 / 로컬 호스트만 |
| LOAD_ITERATIONS | 12 / 1~10000 |
| LOAD_VUS | 2 / 1~20 |
| LOAD_MAX_DURATION | 2m / 1~300s 또는 1~10m |
| LOAD_POLL_TIMEOUT_MS | 30000 / 1000~120000 |
| LOAD_RESULTS_DIR | ./build/load-results / 쓰기 가능한 결과 디렉터리 |

입력 범위 위반은 실행 전 거부한다. check 100%, HTTP 실패 0, 종료 건수=요청 건수, polling timeout 0이 임계값이다. 하나라도 위반하면 k6는 실패 종료한다. URL·ID를 지표 태그에서 제외하고 요청 이름을 publish/status로 고정한다. HTTP debug 옵션을 사용하지 않는다.

결과는 지정 디렉터리의 `summary.json`, `summary.csv`다. completion trend는 발행 요청부터 최종 상태를 조회한 시각까지로 polling 지연을 포함한다. 원시 이벤트 타임라인은 아니며 실패 타임아웃은 이 trend 표본에 포함되지 않으므로 timeout counter와 함께 읽는다.

## 독립 smoke 검증과 정리

```powershell
powershell -NoProfile -File scripts/verify-stage5.ps1
```

검증 스크립트는 빈 포트 5개와 임의 비밀번호를 쓰고 고유 Compose 프로젝트를 만든다. JAR·Prometheus·RabbitMQ 기동, k6 12건 종료, DB DLQ 3건, 재처리 1건과 Prometheus 수집값을 검증한다. 종료 시 해당 프로젝트의 앱·컨테이너·볼륨만 정리하며 결과 파일은 `build/retry-storm-stage5-check-*/`에 남긴다. verification.json의 sourceCommit은 실행 시 HEAD이며 미커밋 변경을 포함한 실행이면 그 사실을 별도 기록해야 한다.

수동 실행한 프로젝트는 동일 셸에서 다음으로 종료한다.

```powershell
docker compose -f compose.yaml -f compose.monitoring.yaml down
```

위 명령은 볼륨을 보존한다. Prometheus 보존 한도는 24시간·256MB로 설정했다. 기존 메모리 tracker는 자동 만료되지 않으므로 반복 부하 실행 간 앱을 재시작하고 결과를 따로 보존한다.

## 공식 참고 자료

- [Micrometer Prometheus registry](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html)
- [RabbitMQ Prometheus와 per-object 지표](https://www.rabbitmq.com/docs/prometheus)
- [Prometheus 수집 설정](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)
- [k6 실행 옵션과 임계값](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/)
