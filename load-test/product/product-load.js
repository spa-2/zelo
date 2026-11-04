import { browse, setup, TARGET_PER_10S } from './product-session.js';

// 부하: 목표 트래픽(4.2 RPS)을 5분 동안 보내 기준을 통과하는지 본다
// VU = 4.2 × 스모크 p95(초), 올림(최소 1). 스모크 결과로 계산해 -e VUS=로 넘긴다
// preAllocatedVUs = VU × 2 (최소 5), maxVUs = VU × 10 (팀 기준)
const VUS = Number(__ENV.VUS || 1);

export const options = {
    scenarios: {
        load: {
            executor: 'constant-arrival-rate',
            rate: TARGET_PER_10S,
            timeUnit: '10s',
            duration: '5m',
            preAllocatedVUs: Math.max(VUS * 2, 5),
            maxVUs: VUS * 10,
            exec: 'browse',
        },
    },
    thresholds: {
        'http_req_failed{kind:product}': ['rate<0.01'],
        checks: ['rate>0.99'],
        'http_req_duration{kind:product}': ['p(95)<300'],
        'http_req_duration{name:search}': ['p(95)<300'],
        'http_req_duration{name:detail}': ['p(95)<300'],
        'http_req_duration{name:autocomplete}': ['p(95)<300'],
        'http_req_duration{name:popular}': ['p(95)<300'],
        'http_req_duration{name:popularKeywords}': ['p(95)<300'],
        'http_req_duration{name:categories}': ['p(95)<300'],
    },
};

export { browse, setup };
