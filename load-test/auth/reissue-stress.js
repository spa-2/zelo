import http from 'k6/http';
import { emailUserId } from '../common/user-id.js';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// 토큰 재발급 스트레스 테스트 (인증 파트)
// 목적: 초당 요청 수(RPS)를 단계적으로 올려서, 서버가 "처음 무너지는 단계"와 그때 무엇이 막혔는지(CPU, 스레드, DB 커넥션)를 찾는다.
// 재발급은 BCrypt가 없고 Redis 조회와 JWT 발급이 전부라 로그인보다 훨씬 가볍다 (로컬 스모크에서 요청 하나가 약 1ms).
// 어떤 단계에서 기준(p95, 실패율)을 넘으면 k6가 그 자리에서 테스트를 멈춘다. 멈춘 단계가 곧 한계다.
// 사용법과 설명은 load-test/README.md 참고. 결과는 docs/load-test/results/ 에 파일로 남는다.
//
// 실행 (저장소 루트에서, k6는 도커로 실행):
//   load-test/run-k6.sh load-test/auth/reissue-stress.js -e NOTE="로컬 첫 스트레스"
// 짧게 동작만 확인:
//   load-test/run-k6.sh load-test/auth/reissue-stress.js -e RATES=1,5,10 -e STEP=10s -e RAMP=2s -e WARMUP=3s

// ───────── 1. 설정값: 환경변수(-e 이름=값)로 바꿀 수 있고, 없으면 오른쪽 기본값을 쓴다 ─────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// 앱의 지표 주소. 테스트 중 서버 상태(CPU, 스레드, 커넥션 풀)를 읽어서 결과 표에 넣는다
const METRICS_URL = __ENV.METRICS_URL || 'http://host.docker.internal:9292/actuator/prometheus';
// 준비 단계에서 로그인해서 토큰을 모아 둘 사용자 수 (이메일 사용자 N명, LoadTestUserSeedLoader 참고)
// VU마다 사용자 한 명을 맡으므로, 스트레스 중 VU 번호가 이 수보다 커지면 안 된다 (VU는 MAX_VUS + 워밍업 + 모니터까지 쓴다)
const SETUP_USERS = Number(__ENV.SETUP_USERS || 1000);
const SETUP_BATCH = 12;
const PASSWORD = __ENV.PASSWORD || 'Zelo1234!';
// 앞선 재발급이 실패해서 토큰을 잃고 건너뛴 횟수
const skipped = new Counter('reissue_skipped');
// 실제 요청 수가 목표의 이 비율에 못 미치면 "통과"가 아니라 "미달"로 본다 (토큰을 잃은 VU가 요청을 못 보내는 경우를 걸러냄)
const REACH_LIMIT = 0.9;
// 재발급이 실패한 횟수(상태 코드별)와, 실패 뒤 다시 로그인해서 토큰을 되찾은 횟수
const failures = new Counter('reissue_failures');
const recovered = new Counter('reissue_recovered');
// 응답을 못 받은(상태 0) 실패에서 같은 토큰으로 다시 보낸 결과: 성공이면 서버가 처음 요청을 처리하지 않은 것이고, 이미 교체된 토큰이라는 응답이면 서버는 처리했는데 응답이 유실된 것이다
const retryProcessed = new Counter('reissue_retry_already_processed');
const retryUnknown = new Counter('reissue_retry_unknown');
const retrySucceeded = new Counter('reissue_retry_succeeded');
const FAILURE_STATUSES = ['0', '400', '401', '404', '409', '429', '500', '502', '503'];
// 올려 갈 초당 요청 수. 첫 값 25는 재발급 목표 RPS다 (1만 명 ÷ 액세스 토큰 600초 × 1.5)
// 목표 5 RPS = 2,000명 × 접속자가 2시간 동안 액세스 토큰(10분) 만료마다 재발급(최대 12회) ÷ 7,200초 × 안전계수 1.5 (2시간 내내 접속한다는 가정이라 실제로는 더 낮을 수 있다)
const RATES = (__ENV.RATES || '25,50,100,200,400,800,1200,1600,2000').split(',').map(Number);
const STEP_SECONDS = toSeconds(__ENV.STEP || '60s'); // 한 단계의 길이
const RAMP_SECONDS = toSeconds(__ENV.RAMP || '5s'); // 단계가 바뀔 때 속도를 올리는 데 쓰는 시간 (STEP 안에 포함)
const WARMUP_SECONDS = toSeconds(__ENV.WARMUP || '20s'); // 측정 전에 가볍게 돌리는 구간 (결과에서 제외)
const WARMUP_RATE = Number(__ENV.WARMUP_RATE || 5);
// 이 단계의 기준: p95가 한도를 넘거나 실패율이 한도를 넘으면 "무너졌다"고 보고 멈춘다
// 재발급은 가벼워서(로컬 스모크 p95 약 1ms) 로그인보다 훨씬 낮게 잡았다. 스모크의 p95(기준선)를 보고 필요하면 -e P95_LIMIT_MS=값 으로 바꾼다
const P95_LIMIT_MS = Number(__ENV.P95_LIMIT_MS || 100);
const ERROR_LIMIT = Number(__ENV.ERROR_LIMIT || 0.01);
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_VUS || 20); // 미리 만들어 둘 가상 사용자 수
const MAX_VUS = Number(__ENV.MAX_VUS || 400); // 서버가 느려질 때 k6가 늘릴 수 있는 최대치
const MONITOR_INTERVAL_SECONDS = Number(__ENV.MONITOR_INTERVAL || 2);
// __VU는 시나리오와 상관없이 전체에서 번호가 매겨지고, 사용자는 VU 번호로 정한다. 그래서 워밍업(최대 50)과 모니터(1)의 VU까지 합친 수가 토큰 수를 넘으면 서로 다른 VU가 같은 토큰을 쓴다
if (MAX_VUS + 50 + 1 > SETUP_USERS) {
    throw new Error(`MAX_VUS(${MAX_VUS}) + 워밍업 50 + 모니터 1 이 SETUP_USERS(${SETUP_USERS})를 넘을 수 없습니다`);
}
const RESULT_DIR = __ENV.RESULT_DIR || 'docs/load-test/results/auth';
const COMMIT = __ENV.COMMIT || '(미입력)';
const NOTE = __ENV.NOTE || '';

const STEP_MS = STEP_SECONDS * 1000;
const WARMUP_MS = WARMUP_SECONDS * 1000;
const STEPS = RATES.map((rate, index) => ({ rate, label: stepLabel(index) }));
const TOTAL_SECONDS = WARMUP_SECONDS + STEP_SECONDS * STEPS.length;

// 서버 상태 지표. 단계별로 최대, 평균을 보려고 Trend로 모은다
const SERVER_METRICS = [
    { name: 'server_process_cpu', source: 'process_cpu_usage', trend: new Trend('server_process_cpu') },
    // 컴퓨터 전체 CPU. 앱 CPU가 낮아도 DB, Redis, k6가 같은 머신에서 CPU를 쓰면 여기서 보인다
    { name: 'server_system_cpu', source: 'system_cpu_usage', trend: new Trend('server_system_cpu') },
    { name: 'server_tomcat_busy', source: 'tomcat_threads_busy_threads', trend: new Trend('server_tomcat_busy') },
    { name: 'server_hikari_active', source: 'hikaricp_connections_active', trend: new Trend('server_hikari_active') },
    { name: 'server_hikari_pending', source: 'hikaricp_connections_pending', trend: new Trend('server_hikari_pending') },
    // 풀 크기(설정값). 리포트의 "풀에 도달" 판단에 쓴다
    { name: 'server_hikari_max', source: 'hikaricp_connections_max', trend: new Trend('server_hikari_max') },
];

// ───────── 2. 시나리오와 합격 기준 ─────────
export const options = {
    scenarios: {
        // 단계가 올라가는 본 시험. 속도는 "VU 수"가 아니라 "초당 요청 수"로 직접 정한다 (서버가 느려져도 약속한 속도로 계속 보낸다)
        stress: {
            executor: 'ramping-arrival-rate',
            // k6의 속도 값은 정수만 되어서 "분당 요청 수"로 쓴다 (2.7 RPS = 분당 162건)
            timeUnit: '1m',
            startRate: perMinute(RATES[0]),
            preAllocatedVUs: PRE_ALLOCATED_VUS,
            maxVUs: MAX_VUS,
            startTime: `${WARMUP_SECONDS}s`,
            stages: buildStages(),
        },
        // 테스트가 도는 동안 서버 상태를 주기적으로 읽는다
        monitor: {
            executor: 'constant-vus',
            vus: 1,
            duration: `${TOTAL_SECONDS}s`,
            exec: 'monitor',
        },
        ...warmupScenario(),
    },
    // 준비 단계(사용자 로그인)에 걸리는 시간 여유
    setupTimeout: '600s',
    thresholds: buildThresholds(),
    // 결과에 보여 줄 통계 항목
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function buildStages() {
    const stages = [];
    STEPS.forEach((step) => {
        // 속도를 짧게 올린 뒤(RAMP) 그 속도를 유지한다
        stages.push({ target: perMinute(step.rate), duration: `${RAMP_SECONDS}s` });
        stages.push({ target: perMinute(step.rate), duration: `${STEP_SECONDS - RAMP_SECONDS}s` });
    });
    return stages;
}

function warmupScenario() {
    if (WARMUP_SECONDS <= 0) {
        return {};
    }
    return {
        // JVM과 커넥션 풀이 데워질 때까지 낮은 속도로 먼저 돌린다. 단계 기준에는 들어가지 않는다
        warmup: {
            executor: 'constant-arrival-rate',
            rate: WARMUP_RATE,
            timeUnit: '1s',
            duration: `${WARMUP_SECONDS}s`,
            preAllocatedVUs: 5,
            maxVUs: 50,
        },
    };
}

function buildThresholds() {
    const thresholds = {};
    STEPS.forEach((step, index) => {
        const tag = `{step:${step.label}}`;
        // 기준을 넘으면 abortOnFail로 테스트를 멈춘다. delayAbortEval은 테스트 시작부터 잰 시간이라, 이 단계가 시작되는 시각에 5초를 더해서 단계 시작 직후 표본이 적을 때를 피한다
        const delay = `${WARMUP_SECONDS + index * STEP_SECONDS + 5}s`;
        thresholds[`http_req_duration${tag}`] = [{ threshold: `p(95)<${P95_LIMIT_MS}`, abortOnFail: true, delayAbortEval: delay }];
        thresholds[`http_req_failed${tag}`] = [{ threshold: `rate<${ERROR_LIMIT}`, abortOnFail: true, delayAbortEval: delay }];
        // 200이어도 응답 내용 검사(check)가 실패하면 단계 실패로 본다
        thresholds[`checks${tag}`] = [{ threshold: `rate>${1 - ERROR_LIMIT}`, abortOnFail: true, delayAbortEval: delay }];
        // 아래는 기준이 아니라 단계별 값을 따로 집계해 결과 표에 쓰려는 장치다 (항상 통과하는 값)
        thresholds[`http_reqs${tag}`] = ['count>=0'];
        SERVER_METRICS.forEach((metric) => {
            thresholds[`${metric.name}${tag}`] = ['max>=0'];
        });
    });
    FAILURE_STATUSES.forEach((status) => {
        thresholds[`reissue_failures{status:${status}}`] = ['count>=0'];
    });
    return thresholds;
}

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
    // k6의 경과 시간(currentTestRunDuration)에는 이 준비 단계 시간이 포함돼서, 그대로 쓰면 단계 이름표가 준비 시간만큼 앞서간다.
    // 그래서 준비가 끝난 시각을 따로 넘겨서 "본 시험이 시작된 뒤 흐른 시간"을 직접 계산한다
    return { tokens, startedAt: Date.now() };
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
        return;
    }

    // 요청이 단계 경계를 넘어 끝나도 요청과 check가 같은 단계로 집계되게 시작 시점에 한 번만 구한다
    const step = currentStep(data);
    const res = http.post(`${BASE_URL}/api/auth/reissue`, null, {
        // 쿠키는 k6의 쿠키 저장소 대신 직접 보낸다 (서버가 Secure 쿠키로 내려줘서 http에서는 저장소가 보내지 않는다)
        headers: { Cookie: `refreshToken=${myToken}` },
        jar: new http.CookieJar(),
        timeout: '10s',
        tags: { name: 'reissue', step },
    });

    const newCookie = res.cookies.refreshToken;
    const newToken = newCookie && newCookie.length > 0 ? newCookie[0].value : null;
    check(res, {
        '상태 코드 200': (r) => r.status === 200,
        '새 액세스 토큰 있음': hasAccessToken,
        '새 리프레시 토큰 받음': () => newToken !== null,
   }, { step });
    // 성공하면 새 토큰으로 교체한다
    if (res.status !== 200 || newToken === null) {
        // 원인을 알 수 있게 실패한 응답을 남기고 상태 코드별로 센다
        console.error(`재발급 실패 VU=${__VU} 상태=${res.status} 오류=${res.error_code}`);
        failures.add(1, { status: String(res.status) });
        if (res.status === 0) {
            // 서버가 처음 요청을 처리했는지 알아보려고 같은 토큰으로 한 번 더 보낸다(재시도가 200이어도 첫 요청이 뒤늦게 도착했는지는 알 수 없다). 처리됐다면 "방금 교체된 토큰" 응답(409)이라 세션은 지워지지 않는다
            const retry = http.post(`${BASE_URL}/api/auth/reissue`, null, {
                headers: { Cookie: `refreshToken=${myToken}` },
                jar: new http.CookieJar(),
                timeout: '10s',
                tags: { name: 'retry-reissue' },
            });
            const retryCookie = retry.cookies.refreshToken;
            if (retry.status === 200 && retryCookie && retryCookie.length > 0) {
                retrySucceeded.add(1);
                myToken = retryCookie[0].value;
                return;
            }
            // 방금 교체된 토큰이라는 409만 "서버가 처음 요청을 처리했다"는 증거다. 연결 오류나 5xx 같은 다른 응답은 알 수 없음으로 센다
            if (retry.status === 409) {
                retryProcessed.add(1);
            } else {
                retryUnknown.add(1);
            }
            console.error(`재시도 결과 VU=${__VU} 상태=${retry.status}`);
        }
        // 서버가 이미 토큰을 바꿨을 수 있어 같은 토큰은 다시 쓰지 않고, 로그인으로 새 토큰을 받아 이어간다
        myToken = recoverToken(__VU % data.tokens.length);
        return;
    }
    myToken = newToken;
}

// 실패한 사용자에게 새 리프레시 토큰을 받아 준다. 이 요청은 step 이름표를 붙이지 않아서 단계별 결과(p95, 실패율)에 들어가지 않는다
function recoverToken(index) {
    const res = http.post(
        `${BASE_URL}/api/users/login`,
        JSON.stringify({ email: `load${emailUserId(index + 1)}@test.com`, password: PASSWORD }),
        { headers: { 'Content-Type': 'application/json' }, jar: new http.CookieJar(), timeout: '10s', tags: { name: 'recover-login' } },
    );
    const cookie = res.cookies.refreshToken;
    if (res.status === 200 && cookie && cookie.length > 0) {
        recovered.add(1);
        return cookie[0].value;
    }
    return null;
}

// 서버 지표를 읽어서 현재 단계 이름표를 붙여 저장한다
export function monitor(data) {
    const res = http.get(METRICS_URL, { timeout: '3s', tags: { name: 'metrics' } });
    if (res.status === 200) {
        const step = currentStep(data);
        SERVER_METRICS.forEach((metric) => {
            const value = pickMetric(res.body, metric.source);
            if (value !== null) {
                metric.trend.add(value, { step });
            }
        });
    }
    sleep(MONITOR_INTERVAL_SECONDS);
}

function hasAccessToken(res) {
    try {
        return Boolean(res.json('data.accessToken'));
    } catch (e) {
        return false;
    }
}

// 테스트가 시작된 뒤 흐른 시간으로 지금이 몇 번째 단계인지 계산한다
function currentStep(data) {
    const elapsed = Date.now() - data.startedAt;
    if (elapsed < WARMUP_MS) {
        return 'warmup';
    }
    const index = Math.min(Math.floor((elapsed - WARMUP_MS) / STEP_MS), STEPS.length - 1);
    return stepLabel(index);
}

// Prometheus 텍스트에서 이름이 같은 줄(라벨이 붙은 것 포함)의 값을 모두 더한다. 없으면 null
function pickMetric(body, name) {
    const pattern = new RegExp(`^${name}(\\{[^}]*\\})? ([0-9.eE+-]+)`, 'gm');
    let sum = 0;
    let found = false;
    let match = pattern.exec(body);
    while (match !== null) {
        sum += Number(match[2]);
        found = true;
        match = pattern.exec(body);
    }
    return found ? sum : null;
}

// ───────── 4. 결과 저장: 테스트가 끝나거나 멈추면 k6가 호출한다 ─────────
export function handleSummary(data) {
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const filePrefix = `${RESULT_DIR}/auth-reissue-stress-${stamp}`;
    const report = buildReport(data);
    return {
        // 터미널에는 결과 문서와 같은 내용을 보여 준다
        stdout: `\n${report}\n`,
        [`${filePrefix}.md`]: report,
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
    const thresholdOk = (metric) => {
        const thresholds = data.metrics[metric] && data.metrics[metric].thresholds;
        if (!thresholds) {
            return null;
        }
        // k6 버전에 따라 {ok: true}이거나, 기준을 어겼을 때 true인 값이다
        return Object.keys(thresholds).every((expression) => {
            const result = thresholds[expression];
            return typeof result === 'object' ? result.ok : !result;
        });
    };
    const ms = (v) => (v === null ? '-' : v.toFixed(1));
    const percent = (v) => (v === null ? '-' : `${(v * 100).toFixed(1)}%`);
    const num = (v, digits) => (v === null ? '-' : v.toFixed(digits));

    const rows = STEPS.map((step, index) => {
        const tag = `{step:${step.label}}`;
        const count = value(`http_reqs${tag}`, 'count');
        const executed = count !== null && count > 0;
        const passed = thresholdOk(`http_req_duration${tag}`) !== false && thresholdOk(`http_req_failed${tag}`) !== false && thresholdOk(`checks${tag}`) !== false;
        return {
            step,
            executed,
            status: !executed ? '미실행' : !passed ? '실패' : count / expectedCount(index) < REACH_LIMIT ? '미달' : '통과',
            count: executed ? count : 0,
            // 목표 속도로 보냈다면 이 단계에서 나가야 할 요청 수 (이전 단계에서 올라오는 구간 포함)와 실제 요청 수의 비율
            reach: executed ? count / expectedCount(index) : null,
            p95: executed ? value(`http_req_duration${tag}`, 'p(95)') : null,
            p99: executed ? value(`http_req_duration${tag}`, 'p(99)') : null,
            failed: executed ? value(`http_req_failed${tag}`, 'rate') : null,
            cpu: value(`server_process_cpu${tag}`, 'max'),
            sysCpu: value(`server_system_cpu${tag}`, 'max'),
            poolMax: value(`server_hikari_max${tag}`, 'max'),
            busy: value(`server_tomcat_busy${tag}`, 'max'),
            active: value(`server_hikari_active${tag}`, 'max'),
            pending: value(`server_hikari_pending${tag}`, 'max'),
        };
    });

    const executedRows = rows.filter((row) => row.executed);
    const firstFail = rows.find((row) => row.status === '실패');
    const lastPass = [...executedRows].reverse().find((row) => row.status === '통과');
    const dropped = value('dropped_iterations', 'count') || 0;
    const skippedCount = value('reissue_skipped', 'count') || 0;
    const vusMax = value('vus_max', 'max');

    const lines = [
        '# 인증 토큰 재발급 스트레스 테스트 결과',
        '',
        `- 실행 시각(종료): ${new Date().toISOString()}`,
        `- 대상: ${BASE_URL}`,
        `- 커밋: ${COMMIT}`,
        `- 메모: ${NOTE || '(없음)'}`,
        '',
        '## 조건',
        '',
        `- 단계(RPS): ${RATES.join(' → ')}`,
        `- 한 단계 ${STEP_SECONDS}초(속도 올리는 구간 ${RAMP_SECONDS}초 포함), 시작 전 워밍업 ${WARMUP_SECONDS}초(결과 제외)`,
        `- 멈추는 기준: 단계별 p95 ${P95_LIMIT_MS}ms 초과 또는 실패율 ${(ERROR_LIMIT * 100).toFixed(1)}% 초과`,
        `- 준비: 시작 전에 이메일 사용자(id 끝자리 1~7) ${SETUP_USERS}명을 로그인해서 리프레시 토큰을 모음 (측정 제외). VU마다 한 명을 맡고, 재발급 응답의 새 토큰으로 다음 요청을 보냄`,
        '- 외부 서비스 호출 없음',
        '',
        '## 한계 판정',
        '',
        `- 마지막으로 통과한 단계: ${lastPass ? `${lastPass.step.rate} RPS` : '없음'}`,
        `- 처음 실패한 단계: ${firstFail ? `${firstFail.step.rate} RPS` : '없음 (모든 단계 통과, 한계는 마지막 단계보다 높다)'}`,
        ...(firstFail ? hintLines(firstFail, P95_LIMIT_MS, ERROR_LIMIT) : []),
        `- k6가 못 보낸 요청(dropped_iterations): ${dropped} / 사용한 최대 VU: ${vusMax === null ? '-' : vusMax}`,
        `- 목표 속도에 못 미친 단계(도달률 ${REACH_LIMIT * 100}% 미만): ${rows.filter((row) => row.status === '미달').map((row) => `${row.step.rate} RPS`).join(', ') || '없음'}`,
        `- 재발급 실패 횟수(상태 코드별): ${FAILURE_STATUSES.map((status) => [status, value(`reissue_failures{status:${status}}`, 'count')]).filter(([, count]) => count).map(([status, count]) => `${status}번 ${count}회`).join(', ') || '없음'} (0번은 응답을 못 받은 연결 오류)`,
        `- 응답을 못 받은 실패의 재시도 결과: 재시도 성공 ${value('reissue_retry_succeeded', 'count') || 0}회 / 이미 처리했는데 응답이 유실됨(재시도가 409) ${value('reissue_retry_already_processed', 'count') || 0}회 / 알 수 없음(재시도도 409가 아닌 실패) ${value('reissue_retry_unknown', 'count') || 0}회`,
        `- 실패 뒤 다시 로그인해서 토큰을 되찾은 횟수: ${value('reissue_recovered', 'count') || 0} / 되찾지 못해 요청을 건너뛴 횟수: ${skippedCount}`,
        '',
        '## 단계별 결과',
        '',
        '| 단계 | 목표 RPS | 요청 수 | 도달률 | p95(ms) | p99(ms) | 실패율 | 앱 CPU 최대 | 전체 CPU 최대 | Tomcat 스레드 최대 | Hikari 사용 최대 | Hikari 대기 최대 | 판정 |',
        '| -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |',
        ...rows.map((row) => `| ${row.step.label} | ${row.step.rate} | ${row.count} | ${row.reach === null ? '-' : `${(row.reach * 100).toFixed(0)}%`} | ${ms(row.p95)} | ${ms(row.p99)} | ${percent(row.failed)} | ${percent(row.cpu)} | ${percent(row.sysCpu)} | ${num(row.busy, 0)} | ${num(row.active, 0)} | ${num(row.pending, 0)} | ${row.status} |`),
        '',
        '- 앱 CPU는 컴퓨터 전체 코어 대비 앱 프로세스의 사용 비율이다 (8코어에서 12.5%가 코어 1개). 전체 CPU는 앱, DB, Redis, k6를 모두 합친 컴퓨터 전체 사용 비율이다',
        '- 각 지표의 최대는 2초마다 찍은 값 중 최대라서, 서로 다른 순간의 값일 수 있다',
        '- 실패한 단계는 도중에 멈췄을 수 있어서 요청 수가 적을 수 있다',
        '',
        '## 환경 (직접 채우기)',
        '',
        '- 머신(CPU 코어 / 메모리): ',
        '- DB, Redis: 로컬 docker compose',
        '- 더미 데이터: 사용자 30000명',
        '- 로그 레벨(SQL 로그 끔 여부): ',
        '- k6: 도커로 실행',
        '',
        '## 해석 메모 (직접 채우기)',
        '',
        '- ',
        '',
    ];
    return lines.join('\n');
}

// 처음 실패한 단계의 서버 지표로 원인을 짐작하는 참고 문장 (확정이 아니라 단서다)
// 한 단계에서 목표 속도를 지켰다면 나갔어야 할 요청 수. 속도를 올리는 구간은 이전 단계와 이번 단계의 평균 속도로 계산한다
function expectedCount(index) {
    const rate = STEPS[index].rate;
    const previous = index === 0 ? rate : STEPS[index - 1].rate;
    return ((previous + rate) / 2) * RAMP_SECONDS + rate * (STEP_SECONDS - RAMP_SECONDS);
}

function hintLines(row, p95Limit, errorLimit) {
    const hints = [];
    if (row.p95 !== null && row.p95 > p95Limit) {
        hints.push(`p95 ${row.p95.toFixed(0)}ms가 한도 ${p95Limit}ms를 넘었다`);
    }
    if (row.failed !== null && row.failed > errorLimit) {
        hints.push(`실패율 ${(row.failed * 100).toFixed(1)}%가 한도를 넘었다`);
    }
    const clues = [];
    if (row.cpu !== null && row.cpu >= 0.7) {
        clues.push(`앱 CPU 최대 ${(row.cpu * 100).toFixed(0)}%로 거의 포화`);
    }
    if (row.sysCpu !== null && row.sysCpu >= 0.85) {
        clues.push(`컴퓨터 전체 CPU 최대 ${(row.sysCpu * 100).toFixed(0)}%로 거의 포화 (앱 외 DB, Redis, k6 포함)`);
    }
    if (row.pending !== null && row.pending > 0) {
        clues.push(`Hikari 대기 커넥션 최대 ${row.pending.toFixed(0)}개 (DB 커넥션 풀이 모자람)`);
    }
    if (row.active !== null && row.poolMax !== null && row.active >= row.poolMax) {
        clues.push(`Hikari 사용 중 커넥션이 풀 크기(${row.poolMax.toFixed(0)})에 도달`);
    }
    if (row.busy !== null && row.busy >= 200) {
        clues.push(`Tomcat 스레드 최대 ${row.busy.toFixed(0)}개로 기본 한도(200)에 도달`);
    }
    return [
        `- 실패 사유: ${hints.length ? hints.join(', ') : '(기준 위반 내용을 표에서 확인)'}`,
        `- 서버 지표 단서(참고): ${clues.length ? clues.join(' / ') : '뚜렷한 포화 신호 없음 (k6나 컴퓨터 자체의 한계도 의심해 볼 것)'}`,
    ];
}

function stepLabel(index) {
    return `s${index < 9 ? '0' : ''}${index + 1}`;
}

function perMinute(ratePerSecond) {
    return Math.max(1, Math.round(ratePerSecond * 60));
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
    return total;
}

function withoutSetupData(data) {
    const { setup_data: removed, ...rest } = data;
    return rest;
}
