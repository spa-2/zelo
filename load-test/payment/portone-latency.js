import http from 'k6/http';
import { check } from 'k6';

// 포트원 테스트 채널 응답 시간 측정. 결과 avg를 zelo.portone.fake-delay 값으로 쓴다 (테스트 채널이라 실제 청구 없음)
// PORTONE_API_SECRET=... STORE_ID=... PAYMENT_ID=결제된_테스트_결제ID BILLING_KEY=테스트_빌링키 k6 run load-test/payment/portone-latency.js
// 시크릿·빌링키는 환경변수로만 넘기고 파일이나 결과에 남기지 않는다
const BASE = 'https://api.portone.io';
const HEADERS = {
    Authorization: `PortOne ${__ENV.PORTONE_API_SECRET}`,
    'Content-Type': 'application/json',
};

export const options = {
    scenarios: {
        // 결제 조회 30번 (1초에 1번)
        getPayment: {
            executor: 'constant-arrival-rate', rate: 1, timeUnit: '1s', duration: '30s',
            preAllocatedVUs: 1, maxVUs: 2, exec: 'getPayment',
        },
        // 빌링키 결제 20번 (2초에 1번). 결제 조회가 끝난 뒤 시작
        billingPay: {
            executor: 'constant-arrival-rate', rate: 1, timeUnit: '2s', duration: '40s', startTime: '35s',
            preAllocatedVUs: 1, maxVUs: 2, exec: 'billingPay',
        },
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'max'],
    // 요약에 API별 줄이 나오게 항상 통과하는 기준을 둔다
    thresholds: {
        'http_req_duration{name:getPayment}': ['max>=0'],
        'http_req_duration{name:billingPay}': ['max>=0'],
    },
};

export function getPayment() {
    const res = http.get(`${BASE}/payments/${__ENV.PAYMENT_ID}`, { headers: HEADERS, tags: { name: 'getPayment' } });
    check(res, { '조회 200': (r) => r.status === 200 });
}

export function billingPay() {
    const paymentId = `latency-${Date.now()}-${__VU}`;
    const res = http.post(`${BASE}/payments/${paymentId}/billing-key`, JSON.stringify({
        storeId: __ENV.STORE_ID, billingKey: __ENV.BILLING_KEY, orderName: '응답 시간 측정',
        amount: { total: 1000 }, currency: 'KRW',
    }), { headers: HEADERS, tags: { name: 'billingPay' } });
    check(res, { '결제 200': (r) => r.status === 200 });
}
