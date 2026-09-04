import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

function integer(name, fallback, min, max) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(name + ' 설정 범위를 벗어났습니다.');
  }
  return value;
}

const base = __ENV.BASE_URL || 'http://host.docker.internal:8080';
if (!/^http:\/\/(localhost|127\.0\.0\.1|host\.docker\.internal)(:[0-9]{1,5})?$/.test(base)) {
  throw new Error('무료 로컬 실험 주소만 허용합니다.');
}
const iterations = integer('ITERATIONS', 12, 1, 10000);
const vus = integer('VUS', 2, 1, 20);
const timeout = integer('POLL_TIMEOUT_MS', 30000, 1000, 120000);
const maxDuration = __ENV.MAX_DURATION || '2m';
if (!/^([1-9]|[1-9][0-9]|1[0-9][0-9]|2[0-9][0-9]|300)s$|^([1-9]|10)m$/.test(maxDuration)) {
  throw new Error('최대 실행 시간은 1~300초 또는 1~10분이어야 합니다.');
}

const terminal = new Counter('lab_terminal');
const timedOut = new Counter('lab_poll_timeouts');
const completion = new Trend('lab_completion_ms', true);

export const options = {
  maxRedirects: 0,
  scenarios: {
    messages: { executor: 'shared-iterations', vus, iterations, maxDuration, gracefulStop: '5s' },
  },
  systemTags: ['status', 'method', 'name', 'scenario', 'check', 'expected_response'],
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    lab_terminal: ['count==' + iterations],
    lab_poll_timeouts: ['count==0'],
  },
};

export default function () {
  timedOut.add(0);
  const failures = exec.scenario.iterationInTest % 4;
  const started = Date.now();
  const published = http.post(base + '/api/v1/messages', JSON.stringify({
    payload: '로컬 부하 검증 합성 메시지',
    failuresBeforeSuccess: failures,
  }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'publish' }, timeout: '5s',
  });
  if (!check(published, { '발행 202': r => r.status === 202 })) return;
  const id = published.json('messageId');
  while (Date.now() - started < timeout) {
    const response = http.get(base + '/api/v1/messages/' + id, {
      tags: { name: 'status' }, timeout: '5s',
    });
    if (response.status !== 200) {
      check(response, { '상태 조회 200': r => r.status === 200 });
      return;
    }
    const result = response.json();
    if (['SUCCEEDED', 'FAILED', 'PERSISTENCE_FAILED'].includes(result.state)) {
      terminal.add(1, { outcome: result.state });
      completion.add(Date.now() - started);
      check(result, {
        '예상 종료 상태': r => r.state === (failures === 3 ? 'FAILED' : 'SUCCEEDED'),
        '정확한 시도 예산': r => r.attemptCount === Math.min(failures + 1, 3),
      });
      return;
    }
    sleep(0.05);
  }
  timedOut.add(1);
  check(false, { '제한 시간 내 종료': value => value });
}

export function handleSummary(data) {
  const rows = ['metric,statistic,value'];
  for (const [metric, item] of Object.entries(data.metrics)) {
    for (const [statistic, value] of Object.entries(item.values)) {
      rows.push([metric, statistic, value].join(','));
    }
  }
  return {
    '/results/summary.json': JSON.stringify(data, null, 2),
    '/results/summary.csv': rows.join('\n') + '\n',
    stdout: '로컬 검증 요약을 summary.json과 summary.csv에 저장했습니다.\n',
  };
}
