import { session } from './quote-session.js';

// 스모크: VU 1명으로 1분 동안 견적 흐름이 오류 없이 도는지 보고, p95를 잰다 (부하 테스트 VU 계산용)
// 실행 방법은 load-test/quote/README.md
export const options = {
    scenarios: {
        smoke: { executor: 'constant-vus', vus: 1, duration: '1m', exec: 'session' },
    },
    // 하나라도 실패하면 본 테스트로 넘어가지 않는다. 요청별 p95가 요약에 나오게 기준을 둔다
    thresholds: {
        'http_req_failed{kind:quote}': ['rate==0'],
        'checks{kind:quote}': ['rate==1'],
        'http_req_duration{kind:quote}': ['max>=0'],
        'http_req_duration{kind:quote,name:issueQuote}': ['max>=0'],
        'http_req_duration{kind:quote,name:changeQuantity}': ['max>=0'],
        'http_req_duration{kind:quote,name:removeItem}': ['max>=0'],
        'iteration_duration{scenario:smoke}': ['max>=0'],
    },
};

export { session };
