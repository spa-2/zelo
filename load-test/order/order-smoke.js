import { session } from './order-session.js';

// 스모크: VU 1명으로 1분 동안 주문 흐름이 오류 없이 도는지 보고, 주문 요청 p95를 잰다 (부하 테스트 VU 계산용)
// 실행 방법은 load-test/order/README.md
export const options = {
    scenarios: {
        smoke: {
            executor: 'constant-vus',
            vus: 1,
            duration: '1m',
            exec: 'session',
        },
    },
    // 하나라도 실패하면 본 테스트로 넘어가지 않는다. API별 줄이 요약에 나오게 항상 통과하는 기준도 둔다
    thresholds: {
        'http_req_failed{kind:order}': ['rate==0'],
        'http_req_duration{kind:order}': ['max>=0'],
        'http_req_duration{name:createConfirmation}': ['max>=0'],
        'http_req_duration{name:createOrder}': ['max>=0'],
        'http_req_duration{name:getOrderDetail}': ['max>=0'],
        checks: ['rate==1'],
    },
};

export { session };
