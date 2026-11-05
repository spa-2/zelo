import http from 'k6/http';
import { check } from 'k6';

// 상품 API 요청 모음. 상품 부하 테스트와 다른 도메인·전체 시나리오가 같이 쓴다
//   import { loadProductData, browseFlow } from '../product/product-api.js';
//   export function setup() { return { product: loadProductData() }; }
//   export function userFlow(data) { browseFlow(data.product); ... }
// 요청마다 kind:product 태그가 붙어서 다른 도메인 요청과 섞여도 {kind:product}로 상품만 따로 볼 수 있다
// 상품 API는 로그인 없이 불러서 토큰·사용자 범위가 필요 없다
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 시드 재료 이름 중 자주 찾을 만한 것
export const KEYWORDS = ['감자', '당근', '양파', '대파', '오이', '사과', '쌀', '소고기', '돼지고기', '삼겹살',
    '닭고기', '계란', '김치', '두부', '라면', '고추장', '된장', '우유', '식빵'];
const SORTS = ['SALES', 'PRICE_ASC', 'PRICE_DESC', 'LATEST'];

// 부하 테스트 상품 시드(LoadTestProductSeedLoader)는 LT-000001부터 번호가 이어진다 (기본 시드 825개를 빼면 LT-099175까지)
// 기본 시드 개수가 늘면 끝 번호가 줄어서, 앞 95,000개만 써서 없는 코드를 부르지 않게 한다
const LT_PRODUCT_COUNT = Number(__ENV.LT_PRODUCT_COUNT || 95000);
// 상세 중 이 비율은 판매량 상위 100개(자주 보는 상품, 캐시에 남아 있음), 나머지는 아무 상품(캐시에 없을 때가 많음)
const HOT_DETAIL_RATIO = 0.3;
// 실패 원인을 남기려고 VU마다 처음 몇 건만 상태 코드와 본문을 찍는다. 다 찍으면 로그가 넘친다
const FAILURE_LOG_LIMIT = 3;
let loggedFailures = 0;

// setup에서 한 번 부른다. 카테고리 ID와 판매량 상위 100개 상품 코드를 준비한다
// 준비 요청은 kind 태그가 없어서 상품 측정에 안 들어간다
export function loadProductData() {
    const categories = http.get(`${BASE_URL}/api/categories`, { tags: { name: 'setup' } }).json('data');
    const categoryIds = categories.flatMap((category) => category.children.map((child) => child.categoryId));
    const top = http.get(`${BASE_URL}/api/products?sort=SALES&size=100`, { tags: { name: 'setup' } }).json('data');
    if (top.totalElements < 90000) {
        throw new Error(`판매 중 상품이 ${top.totalElements}개뿐. 상품 10만 개 시드를 넣고 띄웠는지 확인`);
    }
    return { categoryIds, hotCodes: top.content.map((product) => product.productCode) };
}

export function getCategories() {
    return get('/api/categories', 'categories');
}

export function getPopular() {
    return get('/api/products/popular', 'popular');
}

export function getPopularKeywords() {
    return get('/api/products/popular-keywords', 'popularKeywords');
}

// 입력하는 중인 글자로 부른다. 안 주면 검색어 앞 한 글자
export function autocomplete(typed = random(KEYWORDS).slice(0, 1)) {
    return get(`/api/products/autocomplete?keyword=${encodeURIComponent(typed)}`, 'autocomplete');
}

// 검색어로 찾거나 카테고리로 둘러본다. 옵션을 안 주면 반반
export function search(data, { keyword, categoryId, inStock, sort = random(SORTS), page = 0 } = {}) {
    const byKeyword = keyword !== undefined || (categoryId === undefined && Math.random() < 0.5);
    const query = byKeyword
        ? `keyword=${encodeURIComponent(keyword ?? random(KEYWORDS))}`
        : `categoryId=${categoryId ?? random(data.categoryIds)}&inStock=${inStock ?? Math.random() < 0.3}`;
    return get(`/api/products?${query}&sort=${sort}&page=${page}`, 'search');
}

export function getDetail(productCode) {
    return get(`/api/products/${productCode}`, 'detail');
}

export function randomProductCode(data) {
    if (Math.random() < HOT_DETAIL_RATIO) {
        return random(data.hotCodes);
    }
    return `LT-${String(1 + Math.floor(Math.random() * LT_PRODUCT_COUNT)).padStart(6, '0')}`;
}

// 전체 시나리오 A. 둘러보기 (요청 9개)
// 카테고리 → 인기 상품 → 인기 검색어 → 자동완성 2번 → 검색 → 검색 2쪽 → 상세 2번
// 검색 결과에서 상세를 고르는 것처럼, 검색 응답의 상품을 먼저 쓰고 없으면 아무 상품을 본다
export function browseFlow(data) {
    getCategories();
    getPopular();
    getPopularKeywords();
    const keyword = random(KEYWORDS);
    autocomplete(keyword.slice(0, 1));
    autocomplete(keyword.slice(0, 2));
    const sort = random(SORTS);
    const first = search(data, { keyword, sort });
    search(data, { keyword, sort, page: 1 });
    const found = first.status === 200 ? first.json('data.content').map((product) => product.productCode) : [];
    getDetail(found.length > 0 ? random(found) : randomProductCode(data));
    getDetail(randomProductCode(data));
}

// 서버가 막혀도 요청이 오래 매달리지 않게 끊는다 (k6 기본 60초). 상품 조회가 10초 넘으면 실패로 본다
const TIMEOUT = __ENV.REQUEST_TIMEOUT || '10s';

function get(path, name) {
    const res = http.get(`${BASE_URL}${path}`, { tags: { name, kind: 'product' }, timeout: TIMEOUT });
    const ok = check(res, { '200': (r) => r.status === 200 }, { kind: 'product' });
    if (!ok && loggedFailures < FAILURE_LOG_LIMIT) {
        loggedFailures++;
        // status 0은 서버 응답 없이 연결 단계에서 실패한 것 (error에 원인)
        console.warn(`상품 요청 실패 name=${name} status=${res.status} error=${res.error} body=${String(res.body).slice(0, 200)}`);
    }
    return res;
}

export function random(list) {
    return list[Math.floor(Math.random() * list.length)];
}
