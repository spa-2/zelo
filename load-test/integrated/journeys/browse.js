import { KEYWORDS, randomProductCode } from '../../product/product-api.js';
import { completeVisit, get, random, startVisit, think } from '../common.js';

// A. 둘러보기 (계획서 9-2 A). 로그인 없음, 요청 9개
// 카테고리 → 인기 상품 → 인기 검색어 → 자동완성 2번 → 검색 → 검색 2쪽 → 상세 2번
// 검색어는 흔한 것 70%, 드문 것 25%, 결과 없는 것 5%
//   흔한 것: 상품 테스트(product-api.js)의 KEYWORDS. 재료마다 상품 700~7,750개
//   드문 것: 상품이 약 240개뿐인 재료 (2026-11-06 로컬 시드에서 셈)
//   결과 없는 것: 상품 이름에 없는 말 (로컬 시드에서 0개 확인)
const RARE_KEYWORDS = ['콩자반', '인절미', '전복', '호두', '닭갈비', '깍두기'];
const NO_RESULT_KEYWORDS = ['캐비아', '트러플', '랍스터', '초콜릿케이크'];
const SORTS = ['SALES', 'PRICE_ASC', 'PRICE_DESC', 'LATEST'];

export function browse(data) {
    startVisit();
    get('/api/categories', { name: 'categories', api: 'read' });
    think();
    get('/api/products/popular', { name: 'popular', api: 'read' });
    think();
    get('/api/products/popular-keywords', { name: 'popularKeywords', api: 'read' });
    think();

    const keyword = pickKeyword();
    get(`/api/products/autocomplete?keyword=${encodeURIComponent(keyword.slice(0, 1))}`, { name: 'autocomplete', api: 'read' });
    think();
    get(`/api/products/autocomplete?keyword=${encodeURIComponent(keyword.slice(0, 2))}`, { name: 'autocomplete', api: 'read' });
    think();

    const sort = random(SORTS);
    const first = get(`/api/products?keyword=${encodeURIComponent(keyword)}&sort=${sort}&page=0`, { name: 'search', api: 'read' });
    think();
    get(`/api/products?keyword=${encodeURIComponent(keyword)}&sort=${sort}&page=1`, { name: 'search', api: 'read' });
    think();

    // 검색 결과에서 상세를 고른다. 결과가 없으면 아무 상품을 본다
    const found = first.ok ? first.json('data.content').map((product) => product.productCode) : [];
    get(`/api/products/${found.length > 0 ? random(found) : randomProductCode(data)}`, { name: 'detail', api: 'read' });
    think();
    get(`/api/products/${randomProductCode(data)}`, { name: 'detail', api: 'read' });
    // 상품 조회 실패는 방문을 끊지 않는다 (화면도 다음 화면으로 넘어간다). 실패는 http_req_failed와 check로 센다
    completeVisit();
}

function pickKeyword() {
    const pick = Math.random();
    if (pick < 0.7) {
        return random(KEYWORDS);
    }
    return pick < 0.95 ? random(RARE_KEYWORDS) : random(NO_RESULT_KEYWORDS);
}
