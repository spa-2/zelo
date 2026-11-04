import http from 'k6/http';
import { check } from 'k6';

// 포트원 테스트 채널 취소 응답 시간 측정. 결과 avg를 zelo.portone.fake-cancel-delay 값으로 쓴다 (테스트 채널이라 실제 청구 없음)
// 반복마다 빌링키로 1,000원을 결제하고 바로 같은 금액을 취소한다. 취소 요청(name:cancelPayment)만 본다
// PORTONE_API_SECRET=... STORE_ID=... BILLING_KEY=테스트_빌링키 k6 run load-test/refund/portone-cancel-latency.js
// 시크릿·빌링키는 환경변수로만 넘기고 파일이나 결과에 남기지 않는다
const BASE = 'https://api.portone.io';
const AMOUNT = 1000;
const HEADERS = {
    Authorization: `PortOne ${__ENV.PORTONE_API_SECRET}`,
    'Content-Type': 'application/json',
};

export const options = {
    scenarios: {
        // 결제 → 취소 20번 (2초에 1번)
        cancel: {
            executor: 'constant-arrival-rate', rate: 1, timeUnit: '2s', duration: '40s',
            preAllocatedVUs: 1, maxVUs: 2,
        },
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'max'],
    // 요약에 API별 줄이 나오게 항상 통과하는 기준을 둔다
    thresholds: {
        'http_req_duration{name:cancelPayment}': ['max>=0'],
    },
};

export default function () {
    const paymentId = `cancel-latency-${Date.now()}-${__VU}`;
    const pay = http.post(`${BASE}/payments/${paymentId}/billing-key`, JSON.stringify({
        storeId: __ENV.STORE_ID, billingKey: __ENV.BILLING_KEY, orderName: '취소 응답 시간 측정',
        amount: { total: AMOUNT }, currency: 'KRW',
    }), { headers: HEADERS, tags: { name: 'billingPay' } });
    if (!check(pay, { '결제 200': (r) => r.status === 200 })) {
        return;
    }

    const cancel = http.post(`${BASE}/payments/${paymentId}/cancel`, JSON.stringify({
        storeId: __ENV.STORE_ID, reason: '취소 응답 시간 측정', amount: AMOUNT, currentCancellableAmount: AMOUNT,
    }), { headers: HEADERS, tags: { name: 'cancelPayment' } });
    check(cancel, { '취소 200': (r) => r.status === 200 });
}