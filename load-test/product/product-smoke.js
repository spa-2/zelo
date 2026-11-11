import { browse, setup } from './product-session.js';

// 스모크: VU 1명으로 1분 동안 상품 조회가 오류 없이 도는지 보고, p95를 잰다 (부하 테스트 VU 계산용)
// 실행 방법은 load-test/product/README.md
export const options = {
    scenarios: {
        smoke: {
            executor: 'constant-vus',
            vus: 1,
            duration: '1m',
            exec: 'browse',
        },
    },
    // 하나라도 실패하면 본 테스트로 넘어가지 않는다. API별 p95가 요약에 나오게 기준을 둠
    thresholds: {
        'http_req_failed{kind:product}': ['rate==0'],
        'checks{kind:product}': ['rate==1'],
        'http_req_duration{kind:product}': ['max>=0'],
        'http_req_duration{kind:product,name:search}': ['max>=0'],
        'http_req_duration{kind:product,name:detail}': ['max>=0'],
        'http_req_duration{kind:product,name:autocomplete}': ['max>=0'],
        'http_req_duration{kind:product,name:popular}': ['max>=0'],
        'http_req_duration{kind:product,name:popularKeywords}': ['max>=0'],
        'http_req_duration{kind:product,name:categories}': ['max>=0'],
    },
};

export { browse, setup };
