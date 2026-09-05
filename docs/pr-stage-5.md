# 5단계: Micrometer·로컬 Prometheus·k6

## 검토 순서

4단계 PR #5가 아직 OPEN이므로 base는 `feat/stage-4-jpa-dlq`입니다. 이 PR은 5단계 변경만 보여줍니다. #5를 먼저 검토·merge한 뒤 이 PR의 base를 main으로 변경해 주세요. 자동 merge는 하지 않았습니다.

## 작업과 변경 전후

| 항목 | 이전 | 변경 후 |
| --- | --- | --- |
| 처리 관측 | 메시지별 상태 API | CONSUME/REPLAY 시도·재시도·종료 시간 histogram |
| DLQ 집계 | DB 행 조회 | 커밋 후 신규·중복 저장, 저장 실패·재처리 충돌 집계 |
| 큐 관측 | RabbitMQ 관리 UI | 로컬 Prometheus에서 큐별 ready·unacked·전체 depth 수집 |
| 부하 검증 | 수동 개별 발행 | 제한된 k6 발행·최종 상태·시도 예산 검증, JSON/CSV 요약 |
| 검증 자동화 | 4단계 JAR 재시작 | 독립 Compose/JAR/k6/Prometheus smoke와 입력 제한 확인 |

- Prometheus registry 버전은 기존 Boot BOM을 사용하고 Prometheus v3.5.0·k6 1.2.3 이미지를 고정했습니다.
- monitoring Compose override에서만 RabbitMQ plugin과 Prometheus를 실행합니다. Prometheus UI는 loopback에 바인딩하고 RabbitMQ metrics 포트는 호스트에 열지 않습니다.
- 사용자 정의 metric 태그는 enum으로 제한했습니다. payload·ID·자격 증명을 넣지 않습니다.
- k6는 로컬 URL만 허용하고 redirect·사용량 보고를 끕니다. 건수·VU·시간을 제한하며 단순 HTTP 202를 처리 성공으로 세지 않습니다.
- 지표 정의·PromQL·실행 절차·한계는 `docs/observability.md`에 정리했습니다.

## 실제 검증 결과

- 전체 테스트 **24/24 통과**, 실패·오류·skip 0, **1분 50초**.
- 통합 17건, Jitter 단위 5건, 지표 단위 2건. 커밋/롤백 집계, 최초 시도 제외, 소비/재처리 분리와 전체 scrape의 UUID 비노출을 확인했습니다.
- 실제 JAR health UP, Prometheus 앱·RabbitMQ **2개 대상 UP**.
- k6 **12건 종료, check 36/36 통과**, HTTP 실패율 0, polling timeout 0.
- Prometheus 실제 값: 소비 시도 **27회**, 재시도 **15회**, DLQ 신규 **3건**, 재처리 성공 **1건**, 종료 후 작업 큐 depth **0**.
- 외부 URL과 ITERATIONS=0은 네트워크 없는 컨테이너에서 각각 **종료 코드 107**로 실행 전 거부했습니다.
- 전용 검증 컨테이너·볼륨·네트워크·JAR 프로세스는 정리했습니다. 기존 사용자 리소스·중첩 저장소는 변경하지 않았습니다.
- 실제 기능 결과는 `docs/stage5-smoke-result.json`, 로컬 원본 요약은 Git 제외 `build/retry-storm-stage5-check-792ef02f/`에 있습니다.

## 실패 이력과 수정

- 테스트 기본 metrics export 비활성화로 endpoint가 404여서 테스트 관측성을 명시적으로 켰습니다.
- 테스트용 HTTP 클라이언트 지표에서 URL UUID가 발견되어 URI 템플릿으로 바꿨고, 검사를 전체 UUID 패턴 비노출로 강화했습니다.
- PowerShell JSON 배열 건수 처리 오류와 한글 인코딩 문제를 수정했습니다.
- 최초 RabbitMQ 기동 exit 1의 원인은 초기 로그가 없어 확정하지 못했습니다. 동일 plugin의 독립 기동과 후속 전체 기동 2회는 성공했습니다. 재발 시 진단하도록 실패 로그 보존을 추가했습니다.

## 한계와 다음 단계

- 무료 로컬 도구만 사용했으며 결제·유료 서비스는 없습니다. 비밀값과 실행 로그는 커밋하지 않습니다.
- 5초 scrape rate는 1초 실제 bucket이 아닙니다. histogram p95는 추정치이고 k6 completion은 polling 지연을 포함합니다.
- 이번 실행은 기능 smoke이며 Fixed/Jitter 성능 비교가 아닙니다. 영속 계수·전역 exactly-once·PROCESSING 자동 복구 등의 기존 한계도 유지됩니다.
- 사용자 검토와 별도 승인 후 **6단계 반복 비교 실험·JSON/CSV·한국어 블로그**를 시작합니다. 이번 PR에는 6단계 작업이 없습니다.
