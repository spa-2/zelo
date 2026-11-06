import http from 'k6/http';
import exec from 'k6/execution';
import { orderFlow, userIdAt, loginRequest, USER_COUNT } from '../practice/order-session.js';

// 한계 (새 기준): 목표(분당 100세션 = 초당 1.67세션)의 10·30·60·100배로 올려 처음 기준을 넘는 단계를 찾는다
// 주문은 사용자당 1건이라 8,000명 안에서 돌리려고 단계를 20초로 짧게 둔다 (모든 단계 약 6,700명)
// 로그인(비밀번호 확인)이 먼저 병목이 되지 않도록 setup에서 주문 범위 사용자 토큰을 미리 받아 두고, 세션에서는 주문 요청만 보낸다
const TARGET_SESSIONS_PER_SECOND = 100 / 60;
const MULTIPLIERS = (__ENV.MULTIPLIERS || '10,30,60,100').split(',').map(Number);
const STAGE_SECONDS = Number(__ENV.STAGE_SECONDS || 20);
const LOGIN_BATCH = 50;

const scenarios = {};
const thresholds = {};
let offset = 0;
MULTIPLIERS.forEach((multiplier, index) => {
    const name = `x${multiplier}`;
    const rate = Math.round(TARGET_SESSIONS_PER_SECOND * multiplier);
    scenarios[name] = {
        executor: 'constant-arrival-rate',
        rate,
        timeUnit: '1s',
        duration: `${STAGE_SECONDS}s`,
        startTime: `${STAGE_SECONDS * index}s`,
        preAllocatedVUs: Math.max(10, rate),
        maxVUs: Math.max(30, rate * 3),
        exec: 'session',
        gracefulStop: '10s',
        env: { USER_OFFSET: String(offset) },
    };
    // k6가 단계마다 1건을 더 돌 수 있어 10건 여유를 둔다
    offset += rate * STAGE_SECONDS + 10;
    // 에러가 쏟아질 때만 멈춘다
    thresholds[`http_req_failed{kind:order,scenario:${name}}`] = [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '10s' }];
    // p95로는 멈추지 않고 단계별 결과로 판정한다
    thresholds[`http_req_duration{kind:order,scenario:${name}}`] = ['p(95)<1000'];
    thresholds[`http_reqs{kind:order,scenario:${name}}`] = ['count>=0'];
    thresholds[`http_req_duration{name:createConfirmation,scenario:${name}}`] = ['max>=0'];
    thresholds[`http_req_duration{name:createOrder,scenario:${name}}`] = ['max>=0'];
    thresholds[`http_req_duration{name:getOrderDetail,scenario:${name}}`] = ['max>=0'];
    thresholds[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
});

export const options = { setupTimeout: '15m', scenarios, thresholds };

// 주문 범위 사용자 전부 미리 로그인. 한 번에 LOGIN_BATCH명씩 보낸다
export function setup() {
    const tokens = {};
    for (let from = 0; from < USER_COUNT; from += LOGIN_BATCH) {
        const userIds = [];
        for (let index = from; index < Math.min(from + LOGIN_BATCH, USER_COUNT); index++) {
            userIds.push(userIdAt(index));
        }
        const responses = http.batch(userIds.map((userId) => loginRequest(userId)));
        responses.forEach((res, i) => {
            if (res.status !== 200) {
                throw new Error(`로그인 실패 userId=${userIds[i]} status=${res.status}`);
            }
            tokens[userIds[i]] = res.json('data.accessToken');
        });
    }
    // 서버 지표 CSV를 단계별로 나누려고 첫 단계가 시작하는 시각을 남긴다
    console.log(`첫 단계 시작 ${new Date().toISOString()}`);
    return { tokens };
}

export function session(data) {
    const userId = userIdAt(Number(__ENV.USER_OFFSET) + exec.scenario.iterationInTest);
    if (userId === null) {
        exec.test.abort('주문 시드 사용자를 다 씀');
    }
    orderFlow(userId, data.tokens[userId]);
}