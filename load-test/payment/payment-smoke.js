import { session } from './payment-session.js';

// 스모크: VU 1명으로 1분 동안 결제 흐름이 오류 없이 도는지 보고, 결제 요청 p95를 잰다 (부하 테스트 VU 계산용)
// 실행 방법은 load-test/payment/README.md
export const options = {
    scenarios: {
        smoke: {
            executor: 'constant-vus',
            vus: 1,
            duration: '1m',
            exec: 'session',
        },
    },
    // 하나라도 실패하면 본 테스트로 넘어가지 않는다
    thresholds: {
        'http_req_failed{kind:payment}': ['rate==0'],
        'http_req_duration{kind:payment}': ['max>=0'],
        checks: ['rate==1'],
    },
};

export { session };
