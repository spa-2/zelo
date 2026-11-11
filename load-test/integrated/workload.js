import { loadProductData } from '../product/product-api.js';

// 통합 테스트의 목표 트래픽 (계획서 3장). integrated-smoke.js, integrated-load.js, integrated-stress.js가 같이 쓴다
// DAU 5만 × 하루의 20%가 피크 1시간 = 1만 명 ÷ 60분 × 안전계수 1.5 = 분당 방문 250회 (×1)
export const VISITS_PER_MINUTE = 250;
// A 둘러보기 40%, B 견적·질문 30%, C 구매 20%, D 내 주문 확인 10% (계획서 3-2)
export const SHARE = { browse: 0.4, quoteChat: 0.3, purchase: 0.2, myOrders: 0.1 };
// 예상 VU(리틀의 법칙, 계획서 3-3: A 28, B 94, C 46, D 2.5)에 여유를 둔 ×1 값 [미리 만들 VU, 최대 VU]
export const VUS = { browse: [40, 100], quoteChat: [140, 400], purchase: [70, 200], myOrders: [5, 20] };
// 데우기·측정이 끝난 뒤 진행 중인 방문을 기다리는 시간. B(약 75초)가 가장 길어 그보다 길게 잡는다
export const GRACEFUL_STOP = '120s';
// 사용자 번호대 크기 (계획서 3-5). 측정 시나리오는 이 절반부터 써서 데우기와 같은 사용자를 쓰지 않는다
export const USER_COUNTS = { quoteChat: 10000, purchase: Number(__ENV.PURCHASE_USER_COUNT || 5000), myOrders: 2000 };

// 방문 상태(계획서 9-1), 흐름별 방문 완료율, 구매 단계(9-2 C) 건수를 요약에 태그별로 보이게 하는 표시용 기준. 항상 통과한다
export const COUNT_DISPLAY = Object.fromEntries([
    ...['started', 'completed', 'aborted'].map((state) => [`visit{state:${state}}`, ['count>=0']]),
    ...Object.keys(SHARE).map((name) => [`visit_completed{journey:${name}}`, ['rate>=0']]),
    ...['started', 'quote_ready', 'order_tried', 'order_ok', 'payment_tried', 'payment_ok', 'completed']
        .map((step) => [`purchase_step{step:${step}}`, ['count>=0']]),
]);

// arrival-rate의 target은 정수만 받는다 (예: 250 × 0.1 × 0.5 = 12.5는 오류)
export function rate(name, multiplier) {
    return Math.round(VISITS_PER_MINUTE * SHARE[name] * multiplier);
}

// 판매량 상위 상품 코드와 카테고리를 준비한다 (A의 상세 조회). 준비 요청은 측정 태그가 없어서 판정에 안 들어간다
export function setupData() {
    return loadProductData();
}
