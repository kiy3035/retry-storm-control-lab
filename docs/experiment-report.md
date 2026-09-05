# 재시도 정책 로컬 비교 실험

## 질문과 통제 조건

같은 합성 실패 입력에서 재시도 시각의 집중도와 전체 완료시간이 어떻게 달라지는지 관찰한다. 모든 대상 메시지는 최소 두 번 실패하므로 재시도를 실제 발생시킨다. 실패 횟수 패턴은 [2, 2, 2, 3]이다.

- 정책: Fixed 200ms, Exponential(200ms→400ms, Jitter 0), Exponential+Jitter(기준 지연의 ±50%)
- 정책별 3회, 매회 측정 96건, 먼저 워밍업 16건(측정에서 제외)
- 소비자 8개, prefetch 1, 발행 작업자 16개, 최초 포함 최대 3회
- 반복 순서: Fixed→Exponential→Jitter, Exponential→Jitter→Fixed, Jitter→Fixed→Exponential
- 매 실행 앱 재시작, 같은 전용 DB·브로커·Prometheus 사용
- 무작위 Jitter는 운영용 ThreadLocalRandom을 그대로 사용하며 seed를 고정하지 않음. 같은 입력·설정은 재현하지만 같은 시각 수치는 보장하지 않음

Fixed의 총 대기 기준은 400ms이고 지수 정책은 600ms다. 따라서 Fixed와 Jitter의 차이를 Jitter 하나의 인과 효과로 해석하지 않는다. 지수 정책의 Jitter 0 대조군을 함께 사용한다.

## 원본과 분석 방법

원본 manifest에는 입력·계획·수집기·JAR SHA-256, 실행 커밋, 환경과 완료 상태를 저장한다. 정책별 JSON은 메시지별 서버 publishedAt/completedAt/attemptTimestamps, 상태, DLQ와 replay 응답, CPU·queue 시계열과 DB SQL call 전후 누계를 보존한다. 본문·비밀번호·SQL 원문은 결과에 없다.

완료시간은 서버 completedAt−publishedAt이다. 발행 HTTP 시작이나 polling 종료 시각과 다르며, 성공과 DLQ 저장 후 실패가 모두 포함된다. PostgreSQL 저장 시간도 FAILED의 완료시간에 포함된다.

재시도는 각 메시지의 첫 attempt를 제외한다. 해당 실행의 가장 이른 첫 시각을 origin으로 두고 마지막 completedAt까지 0인 구간도 포함한 반개구간 bucket을 만든다. 기본 폭은 1초이며 100ms도 보조로 계산한다. origin 선택에 따라 경계의 최대값이 달라질 수 있다.

분위수는 nearest-rank, 짝수 개 표본의 median은 중앙 두 값의 평균이다. 표의 각 값은 먼저 실행별로 계산한 통계의 반복 간 중앙값이다. 개별 메시지를 독립 반복으로 취급하거나 3회 결과로 유의성을 주장하지 않는다. 전체 mean/min/max/p95/p99와 원본 bucket은 summary.json 및 retry-buckets.json에 보존한다.

CPU는 1초 간격으로 수집한 Java process_cpu_usage 비율, queue depth는 RabbitMQ 표본 최대다. 짧은 순간 피크를 놓칠 수 있다. DB rate는 pg_stat_statements의 runtime 계정 successful SQL calls 차이를 관측 구간 초로 나눈 값이며 BEGIN/COMMIT 등도 포함한다. 수집용 관리자 SQL·migration·워밍업·재처리는 제외한다. 실패한 SQL 실행 건수나 DB 자원 비용과 동의어가 아니다. [PostgreSQL 16 설명](https://www.postgresql.org/docs/16/pgstatstatements.html)

## 실측 결과

실행 ID는 `stage6-20260904T150000Z`(2026-09-05 +09:00)다. 전체 9회가 완료됐으며 실험 코드 커밋은 `40a5f0164d7b35a27dc30bd456b4fe9dfc514667`, 실행 시작 시 추적 파일의 미커밋 변경은 없었다.

| 정책 | 반복 수 | 완료시간 중앙값(ms) | 완료시간 p95(ms) | 1초 재시도 최대 | 100ms 재시도 최대 |
| --- | ---: | ---: | ---: | ---: | ---: |
| FIXED_200 | 3 | 2856.017 | 5049.251 | 39 | 8 |
| EXPONENTIAL | 3 | 4091.569 | 7435.096 | 31 | 8 |
| EXPONENTIAL_JITTER | 3 | 3957.594 | 7270.813 | 28 | 6 |

위 표는 **실행별 통계의 3회 중앙값**이다. [summary.csv](../results/stage6-20260904T150000Z/summary.csv)와 [전체 통계 JSON](../results/stage6-20260904T150000Z/summary.json)에서 실행별 값과 반복 간 min/max도 확인할 수 있다.

- 실제 측정 메시지는 864건이며 최초 처리 성공 648건, DLQ 저장 216건이다. 모든 메시지가 정확히 3회 시도해 총 시도는 2,592회, 그중 재시도는 1,728회다.
- 매 실행 DLQ 발생률 25%, 실패 메시지의 저장 성공률 100%, 합성 장애를 해제한 재처리 성공률 100%였다. 입력이 네 건 중 한 건을 최종 실패시키므로 발생률은 정책의 성능 개선 지표가 아니다. 실제 외부 장애 복구율도 아니다.
- Jitter 정책의 1초 재시도 최대는 반복별 29·28·27, 지수 대조군은 31·31·32였다. 100ms 최대는 Jitter 6·6·7, 대조군 8·8·8이었다. 이번 표본은 분산을 보여주지만 통계적 유의성이나 운영 환경의 보장으로 해석하지 않는다.
- Fixed 완료시간 p95는 세 실행에서 5022.163~5154.714ms, 지수 대조군은 7433.106~7458.844ms, Jitter는 7174.888~7428.502ms였다. Fixed보다 긴 지수 대기의 비용과 분산 효과를 함께 봐야 한다.
- 1초 bucket의 p95는 모든 실행에서 최대값과 같았다. bucket 수가 적으므로 100ms 원본 분포도 [retry-buckets.json](../results/stage6-20260904T150000Z/retry-buckets.json)에 보존했다.
- 큐 depth 표본 최대는 전체 실행에서 82~96, Java CPU 비율 표본 최대는 약 0.226~0.299였다. runtime SQL call rate는 Fixed 약 7.064~7.221/s, 지수 대조군 5.315~5.699/s, Jitter 5.289~5.415/s였다. 재시도 자체는 DB를 호출하지 않고 최종 실패만 저장하므로 낮은 SQL rate를 DB 효율 개선이라고 해석하지 않는다.

환경은 Windows 11 Home build 26200, Intel i5-1135G7, 물리 RAM 8,379,490,304 bytes, Docker 할당 메모리 3,998,879,744 bytes·CPU 8개, Temurin 21.0.8+9, Python 3.12.10, Docker 24.0.7, Compose 2.23.3-desktop.2, PostgreSQL 16.9, RabbitMQ 4.1.4, Prometheus 3.5.0이다. 환경·JAR 및 입력 hash는 [manifest.json](../results/stage6-20260904T150000Z/manifest.json)에 있다.

### 원본 대조

```powershell
python scripts/verify-report.py results/stage6-20260904T150000Z
```

원본 재계산과 summary.json, 반복 집계, retry-buckets.json, summary.csv, 보고서·블로그 표를 대조한다.

## 재현

```powershell
./gradlew.bat --no-daemon test --rerun-tasks
python -m unittest discover -s scripts -p test_analysis.py -v
python scripts/run-experiment.py
python scripts/analyze-experiment.py results/<출력된-run-id>
```

필요 도구는 Java 21, Docker/Compose, Python 3.12(표준 라이브러리만)다. 수집기는 임의 환경 변수 비밀번호와 빈 포트를 만들고 전용 리소스를 정리한다. 데이터 볼륨과 실행 JAR 프로세스는 종료 후 제거하지만 results의 원본과 build의 로컬 로그는 보존한다. 실패 manifest도 지우지 않고 원인과 재실행 결과를 구분한다.

## 한계

합성 장애는 실제 외부 서비스 포화·공유 장애 해제 시각·네트워크 timeout을 모델링하지 않는다. 모든 메시지를 한 번에 제출하지만 HTTP 스케줄링과 RabbitMQ 소비 순서는 매번 완전히 같지 않다. 호스트의 다른 프로세스와 기존 Docker 리소스를 종료하지 않았으며 캐시·GC·스케줄러·계측 비용이 영향을 준다.

DB와 브로커는 반복 간 재사용하므로 완전히 독립된 환경 반복이 아니다. SQL rate 관측 구간에는 전후 통계 조회에 걸린 시간도 포함된다. bucket 수가 적으면 1초 bucket p95가 최대값과 같아질 수 있다. Python은 원본 ISO 시각을 마이크로초 정밀도로 해석하므로 나노초 이하 차이는 분석에서 보존되지 않는다.
