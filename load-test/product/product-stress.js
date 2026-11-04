import { browse, setup, TARGET_PER_10S } from './product-session.js';

// 스트레스: 목표의 1·3·6·10배(4.2~42 RPS)로 올리며 어디서 느려지는지 본다. 단계마다 10초 올리고 65초 유지, 5분
// VU는 부하와 같이 -e VUS=로 넘긴다. 10배라 preAllocatedVUs = VU × 10 (최소 10), maxVUs = VU × 50
// 한계를 찾는 테스트라 기준으로 멈추지 않는다. 단계별 결과는 CSV의 시각으로 나눠 본다
const VUS = Number(__ENV.VUS || 1);
const MULTIPLIERS = [1, 3, 6, 10];

export const options = {
    scenarios: {
        stress: {
            executor: 'ramping-arrival-rate',
            startRate: TARGET_PER_10S,
            timeUnit: '10s',
            stages: MULTIPLIERS.flatMap((multiplier) => [
                { duration: '10s', target: TARGET_PER_10S * multiplier },
                { duration: '65s', target: TARGET_PER_10S * multiplier },
            ]),
            preAllocatedVUs: Math.max(VUS * 10, 10),
            maxVUs: VUS * 50,
            exec: 'browse',
        },
    },
    thresholds: {
        'http_req_failed{kind:product}': ['rate>=0'],
        'http_req_duration{kind:product}': ['max>=0'],
        'http_req_duration{name:search}': ['max>=0'],
        'http_req_duration{name:detail}': ['max>=0'],
        'http_req_duration{name:autocomplete}': ['max>=0'],
        'http_req_duration{name:popular}': ['max>=0'],
        'http_req_duration{name:popularKeywords}': ['max>=0'],
        'http_req_duration{name:categories}': ['max>=0'],
    },
};

export { browse, setup };
