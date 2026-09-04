# 4단계: PostgreSQL JPA DLQ와 버전 기반 재처리

## 작업 내용과 변경 전후

| 항목 | 이전 | 이번 변경 |
| --- | --- | --- |
| 최종 실패 | 메모리 FAILED, 재시작 시 사라짐 | DB DLQ 커밋 후 소비 완료, 재시작 후 조회 |
| 중복 저장 | 영속 저장 없음 | message_id PK와 충돌 무시 INSERT로 동시 중복 방지 |
| 재처리 | 없음 | 목록·상세·동기 재처리 API, 동일 Fixed/Jitter 정책 사용 |
| 동시 재처리 | 제어 없음 | expectedVersion과 JPA @Version, 충돌 409 |
| 저장 장애 | DB 저장 경로 없음 | 무한 재큐잉 없이 parking 큐 격리 |
| 검증 | 전체 10건 | 전체 19건 및 실제 JAR 재시작 검증 스크립트 |

- Flyway V3로 dead_letters 테이블·인덱스를 추가하고 JPA는 validate만 실행합니다.
- 재처리 선점과 완료는 별도 짧은 트랜잭션이며 대기·실행 중 DB 트랜잭션을 유지하지 않습니다.
- API는 payload와 예외 원문을 노출하지 않습니다. 합성 장애 회복용 실패 횟수 override는 선택값입니다.
- 기존 큐를 보존하면서 DLX 구성을 적용하기 위해 작업 큐·routing key를 retry.lab.work.v4로 전환했습니다. 기존 큐는 이전 앱으로 비운 후 전환해야 합니다.
- README, 결정 기록, PROGRESS에 재현 명령과 장애 경계를 기록했습니다.

## 실제 검증 결과

- `gradlew.bat --no-daemon test --rerun-tasks`: **19/19 성공**, 실패·오류·skip 0, **49초**.
- Testcontainers 통합 14건: 기존 5건과 DLQ·재처리 9건. 동시 최초 INSERT, 동일 버전 API 경쟁, 같은 버전을 읽은 두 실제 트랜잭션의 낙관적 락 충돌, DB 저장 실패의 parking 이동, 재전달별 예산을 검증했습니다.
- Jitter 단위 5건도 모두 통과했습니다.
- `scripts/verify-stage4.ps1`: bootJar 성공(13초), PostgreSQL·RabbitMQ healthy, 앱 health UP.
- 실제 JAR 종료·재시작 전후 DLQ PENDING 보존, 최초 시도 3회, 재처리 SUCCEEDED/1회, reprocessCount 1, 최종 version 2, stale version HTTP 409.
- 첫 17건 실행에서 테스트 수신 변환의 trusted-package 오류 1건이 있었습니다. raw 메시지를 명시적 DTO로 읽도록 수정했고 허용 패키지는 확장하지 않았습니다. 수정 후 17/17, 최종 19/19 성공했습니다.
- 전용 Compose 컨테이너·볼륨·네트워크는 검증 후 제거했고 기존 사용자 리소스는 변경하지 않았습니다.

환경: Windows 11 Home build 26200, i5-1135G7, RAM 8,379,490,304 bytes, Temurin 21.0.8+9, Docker 24.0.7, Compose v2.23.3-desktop.2, PostgreSQL 16.9, RabbitMQ 4.1.4. 검증일 2026-09-04 +09:00.

## 보안과 남는 한계

- 무료 로컬 도구만 사용했으며 유료 서비스·결제 등록은 없습니다. 비밀번호는 실행 중 환경 변수로만 주입합니다.
- 일반 메시지 tracker는 여전히 메모리 기반입니다. 재처리 상태는 DLQ API에서 확인합니다.
- 선점 후 장애로 PROCESSING에 남는 행의 자동 복구와 parking 자동 소비는 포함하지 않습니다.
- DB와 RabbitMQ의 원자적 커밋이나 외부 부수 효과 exactly-once를 보장하지 않습니다. classic 큐 DLX는 브로커 동시 장애까지 무손실을 보장하지 않습니다.
- 개인 로컬 합성 실험이며 인증·공개 배포·실제 다건 성능 측정은 이번 범위가 아닙니다.

## 다음 단계

사용자 검토·merge 및 별도 진행 승인 후 5단계 Micrometer·로컬 Prometheus·k6를 구현합니다. 이번 PR에는 5단계 작업을 포함하지 않습니다.
