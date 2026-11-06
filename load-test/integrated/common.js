import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { accessTokenOf, isEmailUser, login } from '../chat/users.js';

// 통합 흐름(journeys/*.js)이 같이 쓰는 요청·태그·카운터 (계획서 docs/load-test/performance-test-plan.md 9장)
// 태그 규칙: name(API), api(read·write·ai), 결제 요청은 kind:payment, 채팅 메시지는 intent(기대 의도)
// journey·phase 태그는 시나리오(integrated-*.js)가 붙인다
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JSON_HEADERS = { 'Content-Type': 'application/json' };
// 서버가 막혀도 요청이 오래 매달리지 않게 끊는다 (k6 기본 60초). 채팅 메시지는 시간 예산 30초보다 길게 둔다
const TIMEOUT = __ENV.REQUEST_TIMEOUT || '40s';
// 실패 원인을 남기려고 VU마다 처음 몇 건만 상태 코드와 X-Trace-Id를 찍는다 (계획서 7-2). 다 찍으면 로그가 넘친다
const FAILURE_LOG_LIMIT = 3;
let loggedFailures = 0;

// 방문 상태 (계획서 9-1): started, completed, aborted(step). 시작하지 못한 방문은 dropped_iterations, 종료로 끊긴 방문은 k6의 interrupted
const visit = new Counter('visit');
// 구매 단계별 도달 건수 (계획서 9-2 C)
const purchaseStep = new Counter('purchase_step');

export function startVisit() {
    visit.add(1, { state: 'started' });
}

export function completeVisit() {
    visit.add(1, { state: 'completed' });
}

export function abortVisit(step) {
    visit.add(1, { state: 'aborted', step });
}

export function countPurchaseStep(step) {
    purchaseStep.add(1, { step });
}

// 요청 사이 생각 시간 1~3초 (계획서 3-4)
export function think() {
    sleep(1 + Math.random() * 2);
}

// 시나리오마다 반복 번호가 0부터 다시 시작해서, 데우기와 측정이 같은 사용자를 쓰지 않게 시나리오 env의 USER_OFFSET만큼 띄운다
export function pickUser(firstUserId, userCount) {
    const offset = Number(__ENV.USER_OFFSET || 0);
    return firstUserId + ((offset + exec.scenario.iterationInTest) % userCount);
}

// 사용자 번호 끝자리로 이메일·소셜 로그인을 고른다 (계획서 3-5, chat/users.js). 실패하면 null
// 소셜 fake는 외부 응답 대기(평균 900ms ±50%)가 있어 기준이 다르므로 loginType 태그로 나눠 판정한다 (계획서 4-1-1)
export function loginUser(userId) {
    const loginType = isEmailUser(userId) ? 'email' : 'social';
    const res = login(BASE_URL, userId, { headers: JSON_HEADERS, tags: { name: 'login', loginType }, timeout: TIMEOUT });
    const token = accessTokenOf(res);
    if (!check(res, { '로그인 200': () => token !== null })) {
        logFailure('login', res);
    }
    return token;
}

export function authHeaders(token) {
    return { ...JSON_HEADERS, Authorization: `Bearer ${token}` };
}

// 화면이 [보내기]마다 새 UUID를 보내는 것과 같게 한다 (채팅 메시지)
export function withIdempotencyKey(headers) {
    return { ...headers, 'Idempotency-Key': crypto.randomUUID() };
}

// tags: { name, api, kind?, intent? }. expected 상태 코드가 아니면 check 실패로 세고 처음 몇 건만 로그를 남긴다
// 돌려주는 값: { ok, status, json(path) }
export function get(path, tags, { headers = JSON_HEADERS, expected = 200 } = {}) {
    return verify(http.get(`${BASE_URL}${path}`, { headers, tags, timeout: TIMEOUT }), tags.name, expected);
}

export function post(path, body, tags, { headers = JSON_HEADERS, expected = 200 } = {}) {
    const payload = body === null ? null : JSON.stringify(body);
    return verify(http.post(`${BASE_URL}${path}`, payload, { headers, tags, timeout: TIMEOUT }), tags.name, expected);
}

// k6 응답 객체에는 속성을 더할 수 없어서, 성공 여부와 본문 읽기를 담은 작은 객체로 돌려준다
function verify(res, name, expected) {
    const ok = check(res, { [`${name} ${expected}`]: (r) => r.status === expected });
    if (!ok) {
        logFailure(name, res);
    }
    return { ok, status: res.status, json: (path) => res.json(path) };
}

function logFailure(name, res) {
    if (loggedFailures >= FAILURE_LOG_LIMIT) {
        return;
    }
    loggedFailures++;
    // status 0은 서버 응답 없이 연결 단계에서 실패한 것 (error에 원인). traceId로 서버 로그를 찾는다
    console.warn(`요청 실패 name=${name} status=${res.status} traceId=${res.headers['X-Trace-Id'] || '-'} error=${res.error}`
        + ` body=${String(res.body).slice(0, 200)}`);
}

// 응답 형식 check (계획서 4-1-2 "응답 형식이 기대와 다름 < 1%"). kind:format으로 모아 판정한다
export function checkFormat(passed, intent) {
    return check(null, { '응답 형식': () => passed }, { kind: 'format', intent });
}

export function random(list) {
    return list[Math.floor(Math.random() * list.length)];
}
