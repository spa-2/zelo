import http from 'k6/http';
import { emailUserId } from '../common/user-id.js';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

// 토큰 재발급 스모크 테스트 (인증 파트)
// 목적: 가상 사용자 1명이 재발급을 연달아 보내서, 부하가 없을 때의 응답 시간(기준선 p95)을 얻는다.
//       이 p95로 다음 단계(부하 테스트)의 VU 수를 계산한다.
// 사용법과 설명은 load-test/README.md 참고. 결과는 docs/load-test/results/ 에 파일로 남는다.
//
// 실행 (저장소 루트에서, k6는 도커로 실행):
//   load-test/run-k6.sh load-test/auth/reissue-smoke.js -e NOTE="로컬 첫 측정"

// ───────── 1. 설정값: 환경변수(-e 이름=값)로 바꿀 수 있고, 없으면 오른쪽 기본값을 쓴다 ─────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// 준비 단계에서 로그인해서 토큰을 모아 둘 사용자 수 (이메일 사용자 N명, LoadTestUserSeedLoader 참고)
// 스모크는 VU가 1~2명이라 적게 둔다. VU 번호가 이 수보다 커지면 안 된다
const SETUP_USERS = Number(__ENV.SETUP_USERS || 10);
const SETUP_BATCH = 10;
const PASSWORD = __ENV.PASSWORD || 'Zelo1234!';
// 앞선 재발급이 실패해서 토큰을 잃고 건너뛴 횟수
const skipped = new Counter('reissue_skipped');
const WARMUP = __ENV.WARMUP || '20s';
const DURATION = __ENV.DURATION || '1m';
const RESULT_DIR = __ENV.RESULT_DIR || 'docs/load-test/results/auth';
const COMMIT = __ENV.COMMIT || '(미입력)';
const NOTE = __ENV.NOTE || '';

// ───────── 2. 시나리오: 언제, 누가, 얼마나 요청을 보낼지 ─────────
export const options = {
    scenarios: {
        // JVM과 커넥션 풀이 데워지기 전의 느린 요청이 결과에 섞이지 않게 먼저 돌리는 구간. 측정에는 쓰지 않는다
        warmup: { executor: 'constant-vus', vus: 1, duration: WARMUP, tags: { phase: 'warmup' } },
        // 실제로 측정하는 구간. 워밍업이 끝난 시점(startTime)에 시작한다
        smoke: { executor: 'constant-vus', vus: 1, duration: DURATION, startTime: WARMUP, tags: { phase: 'smoke' } },
    },
    // 준비 단계(사용자 로그인)에 걸리는 시간 여유
    setupTimeout: '300s',
    // 합격 기준. smoke 구간만 본다. 스모크는 지연을 합격/불합격으로 가르지 않고 기준선을 얻는 것이 목적이다
    thresholds: {
        'http_req_failed{phase:smoke}': ['rate<0.01'], // 실패한 요청이 1% 미만
        'checks{phase:smoke}': ['rate>0.99'], // 아래 check가 99% 이상 통과
        // 아래 둘은 기준이 아니라 smoke 구간의 값을 따로 집계해 결과 파일에 쓰려는 장치다 (항상 통과하는 값)
        'http_req_duration{phase:smoke}': ['p(95)<1000000'],
        'http_reqs{phase:smoke}': ['count>=0'],
    },
    // 결과에 보여 줄 통계 항목
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// ───────── 2-1. 준비 단계: 테스트 시작 전에 한 번만 실행된다 (측정에 들어가지 않는다) ─────────
// 재발급에는 사용자마다 리프레시 토큰이 필요해서, 먼저 로그인해서 토큰을 모아 둔다.
// 서버는 재발급할 때마다 리프레시 토큰을 새로 바꾸고 옛 토큰을 못 쓰게 한다 (옛 토큰을 다시 쓰면 오류, 오래 지난 옛 토큰이면 로그아웃 처리).
// 그래서 한 사용자의 토큰은 항상 한 번에 한 요청만 쓰고, 응답으로 받은 새 토큰을 다음에 써야 한다.
export function setup() {
    const tokens = [];
    for (let start = 1; start <= SETUP_USERS; start += SETUP_BATCH) {
        const requests = [];
        for (let n = start; n < start + SETUP_BATCH && n <= SETUP_USERS; n++) {
            requests.push([
                'POST',
                `${BASE_URL}/api/users/login`,
                JSON.stringify({ email: `load${emailUserId(n)}@test.com`, password: PASSWORD }),
                { headers: { 'Content-Type': 'application/json' }, jar: new http.CookieJar(), timeout: '30s', tags: { name: 'setup-login' } },
            ]);
        }
        http.batch(requests).forEach((res, offset) => {
            const cookie = res.cookies.refreshToken;
            if (res.status !== 200 || !cookie || cookie.length === 0) {
                throw new Error(`준비 단계 로그인 실패 (사용자 load${emailUserId(start + offset)}@test.com, 상태 ${res.status}). 더미 데이터와 서버를 확인하세요`);
            }
            tokens.push(cookie[0].value);
        });
    }
    return { tokens };
}

// ───────── 3. 실제 요청: VU가 반복해서 실행하는 부분 ─────────
// 가상 사용자(VU)마다 사용자 한 명(의 토큰)을 맡는다. 이 변수는 VU마다 따로 있고, 응답으로 받은 새 토큰을 여기에 저장한다.
// 한 VU는 요청을 하나씩만 보내므로 같은 토큰이 동시에 쓰일 일이 없다. 사용자는 VU 번호로 정하므로 VU 번호가 토큰 수(SETUP_USERS)보다 커지면 안 된다.
let myToken;

export default function (data) {
    if (myToken === undefined) {
        myToken = data.tokens[__VU % data.tokens.length];
    }
    if (myToken === null) {
        // 앞선 재발급이 실패해서 이 사용자의 토큰을 잃었다. 같은 토큰을 다시 쓰면 서버가 세션을 지우므로 요청을 건너뛴다
        skipped.add(1);
        // 쉬지 않고 돌면 k6가 CPU를 낭비해서 결과를 흐리므로 잠깐 쉰다
        sleep(1);
        return;
    }

    const res = http.post(`${BASE_URL}/api/auth/reissue`, null, {
        // 쿠키는 k6의 쿠키 저장소 대신 직접 보낸다 (서버가 Secure 쿠키로 내려줘서 http에서는 저장소가 보내지 않는다)
        headers: { Cookie: `refreshToken=${myToken}` },
        jar: new http.CookieJar(),
        timeout: '10s',
        tags: { name: 'reissue' },
    });

    const newCookie = res.cookies.refreshToken;
    const newToken = newCookie && newCookie.length > 0 ? newCookie[0].value : null;
    check(res, {
        '상태 코드 200': (r) => r.status === 200,
        '새 액세스 토큰 있음': hasAccessToken,
        '새 리프레시 토큰 받음': () => newToken !== null,
    });
    // 성공하면 새 토큰으로 교체하고, 실패하면 이 토큰을 더 쓰지 않는다
    if (res.status !== 200 || newToken === null) {
        // 원인을 알 수 있게 실패한 응답을 남긴다 (VU마다 한 번만 나온다)
        console.error(`재발급 실패 VU=${__VU} 상태=${res.status} 응답=${String(res.body).slice(0, 200)}`);
        myToken = null;
        return;
    }
    myToken = newToken;
}

function hasAccessToken(res) {
    try {
        return Boolean(res.json('data.accessToken'));
    } catch (e) {
        // 응답이 JSON이 아니면(서버 다운 등) 토큰이 없는 것으로 본다
        return false;
    }
}

// ───────── 4. 결과 저장: 테스트가 끝나면 k6가 호출한다 ─────────
export function handleSummary(data) {
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const filePrefix = `${RESULT_DIR}/auth-reissue-smoke-${stamp}`;
    return {
        // 터미널에 보이는 요약
        stdout: textSummary(data, { indent: ' ', enableColors: true }),
        // 사람이 읽는 결과 문서
        [`${filePrefix}.md`]: buildReport(data),
        // k6 원본 데이터 (나중에 다시 분석할 때 쓰고, 필요 없으면 지워도 된다)
        // setup_data에는 로그인으로 받은 실제 리프레시 토큰이 들어 있어서 파일에 남기지 않는다
        [`${filePrefix}.json`]: JSON.stringify(withoutSetupData(data), null, 2),
    };
}

function buildReport(data) {
    const value = (metric, key) => {
        const m = data.metrics[metric];
        return m && m.values[key] !== undefined ? m.values[key] : null;
    };
    const ms = (v) => (v === null ? '-' : `${v.toFixed(1)} ms`);
    const percent = (v) => (v === null ? '-' : `${(v * 100).toFixed(2)} %`);

    const duration = 'http_req_duration{phase:smoke}';
    const requests = value('http_reqs{phase:smoke}', 'count');
    // k6가 계산하는 rate는 워밍업 시간까지 포함한 값이라, 측정 구간 시간으로 직접 나눈다
    const rps = requests === null ? null : requests / toSeconds(DURATION);

    const thresholdRows = [];
    Object.keys(data.metrics).forEach((name) => {
        const thresholds = data.metrics[name].thresholds;
        if (!thresholds || !name.includes('phase:smoke')) {
            return;
        }
        Object.keys(thresholds).forEach((expression) => {
            const result = thresholds[expression];
            // k6 버전에 따라 {ok: true}이거나, 기준을 어겼을 때 true인 값이다
            const ok = typeof result === 'object' ? result.ok : !result;
            thresholdRows.push(`| ${name} | ${expression} | ${ok ? '통과' : '실패'} |`);
        });
    });

    return [
        '# 인증 토큰 재발급 스모크 테스트 결과',
        '',
        `- 실행 시각: ${new Date().toISOString()}`,
        `- 대상: ${BASE_URL}`,
        `- 커밋: ${COMMIT}`,
        `- 메모: ${NOTE || '(없음)'}`,
        '',
        '## 조건',
        '',
        `- 가상 사용자(VU) 1명, 워밍업 ${WARMUP}(측정 제외) 후 ${DURATION} 동안 측정`,
        `- 준비: 시작 전에 이메일 사용자(id 끝자리 1~7) ${SETUP_USERS}명을 로그인해서 리프레시 토큰을 모음 (측정 제외)`,
        '- 재발급 때마다 서버가 토큰을 새로 바꾸므로, 응답으로 받은 새 토큰으로 다음 재발급을 보낸다',
        '- 외부 서비스 호출 없음',
        '',
        '## 결과 (측정 구간만)',
        '',
        '| 항목 | 값 |',
        '| -- | -- |',
        `| 총 요청 수 | ${requests === null ? '-' : requests} |`,
        `| 처리량 (RPS) | ${rps === null ? '-' : rps.toFixed(2)} |`,
        `| 평균 | ${ms(value(duration, 'avg'))} |`,
        `| 중앙값 | ${ms(value(duration, 'med'))} |`,
        `| p90 | ${ms(value(duration, 'p(90)'))} |`,
        `| p95 | ${ms(value(duration, 'p(95)'))} |`,
        `| p99 | ${ms(value(duration, 'p(99)'))} |`,
        `| 최대 | ${ms(value(duration, 'max'))} |`,
        `| 실패율 | ${percent(value('http_req_failed{phase:smoke}', 'rate'))} |`,
        `| 체크 통과율 | ${percent(value('checks{phase:smoke}', 'rate'))} |`,
        '',
        '## 합격 기준 판정',
        '',
        '| 지표 | 기준 | 결과 |',
        '| -- | -- | -- |',
        ...thresholdRows,
        '',
        '## 다음 단계 계산 (직접 채우기)',
        '',
        '- 부하 테스트 VU = 올림(목표 RPS × 위 p95(초)), 최소 1',
        '- 이 p95(초): (    ) / 목표 RPS: (    ) / 계산한 VU: (    )',
        '',
        '## 서버 지표 (직접 채우기, collect-metrics.sh 결과를 보고 적기)',
        '',
        '| 항목 | 최대 | 평균 |',
        '| -- | -- | -- |',
        '| 프로세스 CPU | | |',
        '| Tomcat busy 스레드 | | |',
        '| Hikari 사용 중 커넥션 | | |',
        '| Hikari 대기(pending) 커넥션 | | |',
        '| 힙 메모리(MB) | | |',
        '',
        '## 환경 (직접 채우기)',
        '',
        '- 머신(CPU 코어 / 메모리): ',
        '- DB, Redis: 로컬 docker compose',
        '- 더미 데이터: 사용자 30000명',
        '- 로그 레벨(SQL 로그 끔 여부): ',
        '- 외부 서비스: 해당 없음',
        '',
        '## 해석 메모 (직접 채우기)',
        '',
        '- ',
        '',
    ].join('\n');
}

// '1m', '90s', '1m30s' 같은 시간 문자열을 초로 바꾼다
function toSeconds(text) {
    const unit = { h: 3600, m: 60, s: 1 };
    const pattern = /(\d+)([hms])/g;
    let total = 0;
    let match = pattern.exec(text);
    while (match !== null) {
        total += Number(match[1]) * unit[match[2]];
        match = pattern.exec(text);
    }
    return total || 1;
}

function withoutSetupData(data) {
    const { setup_data: removed, ...rest } = data;
    return rest;
}
