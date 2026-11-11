import exec from 'k6/execution';
import { changeQuantity, issueQuote, login, QUOTE_FIRST_USER_ID, QUOTE_USER_COUNT, removeItem } from './quote-api.js';

// quote-smoke.js, quote-load.js, quote-stress.js, quote-limit.js가 같이 쓰는 사용자 1명의 흐름(세션)
// 세션 = 로그인 → 견적 발급 → 첫 상품 수량 +1 → 마지막 상품 빼기. 로그인을 빼면 견적 요청 3개
// 대체 상품·예산 후보 버튼은 fake 메뉴 계획(예산 없음, 품절 없음)으로는 생기지 않아서 뺐다

// 목표: DAU 5만 명 중 피크 1시간에 1만 명, 그중 견적을 받는 50%(전체 시나리오 B 30% + C 20%)가 1번
//   1만 × 50% ÷ 3,600초 × 1.5 = 초당 2.08세션 = 분당 125세션. 견적 요청은 세션당 3개라 6.25 RPS
export const TARGET_SESSIONS_PER_MINUTE = 125;

export function session() {
    // 세션마다 다음 사용자로 넘어가서 같은 사용자가 1분에 여러 번 오지 않는다 (메시지 제한 1분 10번)
    const userId = QUOTE_FIRST_USER_ID + (exec.scenario.iterationInTest % QUOTE_USER_COUNT);
    const token = login(userId);
    if (!token) {
        return;
    }
    const issued = issueQuote(token);
    if (!issued || issued.quote.items.length === 0) {
        return;
    }
    const { roomId } = issued;
    const first = issued.quote.items[0];
    const changed = changeQuantity(token, roomId, issued.quote, first, Math.min(first.quantity + 1, 10));
    if (!changed || changed.items.length < 2) {
        return;
    }
    removeItem(token, roomId, changed, changed.items[changed.items.length - 1]);
}
