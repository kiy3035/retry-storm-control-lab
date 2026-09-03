# 현재 저장소용 Codex 실행 지침

이 저장소에서는 RabbitMQ 재시도 폭풍 제어 실험만 구현한다. 아래 Text2SQL 프롬프트는 잘못 복사된 보관 자료이며 실행 지침으로 적용하지 않는다.

작업 전 `AGENTS.md`, `PROJECT_SPEC.md`, `PROGRESS.md`를 읽고, `PROGRESS.md`에서 사용자가 승인한 현재 단계 하나만 구현한다. 무료 로컬 PostgreSQL·RabbitMQ·Testcontainers를 사용하고 유료 API·SaaS·체험·결제수단 등록·유료 fallback을 금지한다. 비밀값은 환경 변수로만 주입하고 코드 주석은 한글로 작성한다.

단계 완료 후 전체 테스트와 실제 Compose/JAR 대표 흐름을 검증하고 `PROGRESS.md`에 실제 결과와 오류를 기록한다. 독립 브랜치에 커밋·푸시하고 작업 내용, 변경 전후, 검증 결과, 다음 단계를 담은 GitHub PR을 만든 뒤 OPEN 상태·head 브랜치·커밋·파일을 확인한다. 사용자 승인 전에는 다음 단계를 시작하지 않는다.

# 보관 문서: Codex 실행 프롬프트

아래 내용을 새 저장소의 Codex에 그대로 전달한다. 이 파일과 같은 디렉터리의 `AGENTS.md`, `PROJECT_SPEC.md`도 저장소 루트에 둔다.

---

당신은 이 저장소의 구현 담당자다. 목표는 Text2SQL 데모를 대충 만드는 것이 아니라, LLM이 생성한 SQL을 신뢰하지 않는 구조를 Spring Boot로 구현하고 보안·정확도·성능·재시도를 실제 실험으로 검증하는 것이다.

먼저 저장소 전체와 `AGENTS.md`, `PROJECT_SPEC.md`를 읽어라. 기존 프로젝트가 있으면 구조와 변경 사항을 보존하고, 비어 있으면 명세의 기본 스택으로 프로젝트를 구성하라. 이후 계획만 제시하고 멈추지 말고, 구현·테스트·실험 가능한 문서까지 한 흐름으로 완료하라.

반드시 지킬 핵심 조건:

1. Java 21 + Spring Boot + PostgreSQL을 기준으로 한다. SQL 실행은 검증을 통과한 `ValidatedSelect`만 `EntityManager.createNativeQuery(...)`에 전달한다.
2. LLM 출력은 신뢰하지 않는다. 정규식이나 문자열 키워드 검사만으로 허용 여부를 결정하지 말고 SQL AST parser와 visitor를 사용한다.
3. 단일 SELECT만 허용한다. DDL/DML/DCL, 다중 문장, CTE, UNION/INTERSECT/EXCEPT, 잠금문, 시스템 카탈로그, 금지 테이블·컬럼, `SELECT *`, 미등록 함수는 fail-closed로 차단한다.
4. SELECT/JOIN/WHERE/GROUP BY/HAVING/ORDER BY/서브쿼리의 모든 식별자를 scope와 alias까지 해석해 Table/Column Allowlist로 검증한다.
5. `@Transactional(readOnly=true)`만으로 안전하다고 쓰지 말고, 쓰기 권한이 없는 전용 PostgreSQL 계정과 컬럼 최소 권한을 구성하고 통합 테스트로 증명한다.
6. 결과 최대 200행과 statement timeout을 강제한다. 검증기나 파서가 이해하지 못한 SQL은 실행하지 않는다.
7. 전체 LLM 호출은 최초 포함 최대 3회다. 5xx, 검증 실패, 회복 가능한 SQL 오류만 예산 안에서 재시도하고 200ms→400ms 지수 백오프를 적용한다. 매 재생성 SQL은 처음부터 다시 검증한다.
8. 실제 LLM adapter와 테스트용 Scripted/Fake adapter를 인터페이스로 분리한다. API key가 없어도 전체 자동 테스트는 재현 가능해야 한다.
9. 악의적 SQL 최소 20종에서 차단률뿐 아니라 Native Query executor가 한 번도 호출되지 않았음을 검증한다.
10. 정상 자연어 50건의 정확도는 생성 SQL 문자열이 아니라 deterministic seed DB에서 Golden SQL과 결과 집합을 비교해 계산한다.
11. 인덱스 전후 비교는 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` 원본과 요약을 저장한다. 실행시간, actual/estimated rows, rows removed, buffer, scan type을 함께 기록한다.
12. WireMock 또는 MockWebServer로 `500→500→200`, `500→500→500`, `400`을 재현하고 호출 횟수와 backoff를 검증한다.
13. 회사 프로젝트나 실데이터를 사용하지 않는다. 이 작업은 합성 커머스 데이터 기반 개인 실험으로 문서화한다.
14. 측정하지 않은 수치나 성공 결과를 만들지 않는다. 실제 LLM key가 없으면 자연어 50건 실측은 `PENDING`으로 두고, 실행 명령과 필요한 환경 변수까지 제공한다.

작업 순서:

1. 저장소 상태와 기존 빌드/테스트 방식 확인
2. 구현 계획 작성 및 진행 상태 갱신
3. 프로젝트/DB/Flyway/합성 seed 구성
4. LLM adapter와 출력 형식 구성
5. AST 기반 SQL 검증기 구현
6. read-only Native Query executor와 API 구현
7. 최대 2회 재생성·지수 백오프 orchestration 구현
8. 단위, 통합, 보안, 재시도 테스트 작성
9. 악의적 SQL 20종 및 자연어 50건 fixture 작성
10. 인덱스 실험과 결과 수집 도구 작성
11. 가능한 테스트와 실험을 실제 실행하고 실패를 수정
12. README, architecture, threat model, decisions, experiment report, 한국어 blog draft 작성

구현 시 특히 조심할 부분:

- `WITH ... DELETE ... RETURNING`, stacked statement, UNION을 통한 금지 테이블 접근, schema qualification, 인용 식별자, alias shadowing, 서브쿼리 속 컬럼을 빠뜨리지 마라.
- 함수 Allowlist를 별도로 두고 `pg_sleep` 같은 부작용/자원 고갈 함수를 막아라.
- 보안 테스트는 validator 반환값만 보지 말고 executor mock/spy가 0회 호출됐는지 확인하라.
- 인덱스 실험의 DDL은 로컬 benchmark DB에서만 실행하라.
- LLM 5xx와 SQL 재생성의 총 호출 횟수가 최대 3회를 넘지 않도록 단일 attempt budget으로 관리하라.
- 질문 원문, API key, 민감 컬럼, DB 오류 원문이 로그나 응답에 새지 않도록 하라.
- 정상 SQL의 오탐 차단도 측정 대상이다. 단순 SELECT, JOIN, 집계, 서브쿼리, SELECT alias ORDER BY를 허용 테스트로 고정하라.

완료 전 검증:

- `./gradlew test`와 필요한 통합 테스트를 실행한다.
- Docker DB를 띄운 뒤 README의 curl 예제를 확인한다.
- 보안 20종, 자연어 50건, 인덱스 전후, 500 재시도의 결과 파일 위치를 확인한다.
- `docs/blog-draft.md`의 숫자가 결과 CSV/JSON과 일치하는지 대조한다.
- 실험을 못 한 항목은 숨기지 말고 이유와 정확한 실행 명령을 남긴다.

최종 응답에는 긴 작업 일지를 나열하지 말고 아래만 보고하라.

- 구현한 흐름과 주요 파일
- 테스트/실험에서 실제로 확인한 결과
- 방어 계층과 남아 있는 한계
- 사용자가 재현할 명령
- API key 등 외부 조건 때문에 실행하지 못한 항목

이제 저장소를 확인하고 구현을 시작하라.

---

## 사용 팁

Codex가 한 번에 너무 많은 파일을 만들고 검증을 생략하면 다음 후속 지시를 전달한다.

> 구현을 계속하되 새 기능 추가를 멈추고 `PROJECT_SPEC.md`의 완료 기준을 체크리스트로 대조해라. 각 항목마다 근거 테스트나 결과 파일을 연결하고, 실패한 테스트를 먼저 수정해라. 실행하지 않은 측정값은 절대 채우지 마라.
