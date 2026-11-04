import http from 'k6/http';
import { check } from 'k6';

// product-smoke.js, product-load.js, product-stress.js가 같이 쓰는 상품 조회 흐름
// 상품 API는 로그인 없이 부르는 거라 사용자 범위·토큰을 안 씀
// 요청 1번 = 반복 1번. 사용자 1명이 상품을 10번 부른다고 보고 목록·검색 4, 상세 3, 자동완성 2, 인기 상품·인기 검색어·카테고리 1 비율로 고른다
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 목표: 피크 2,000명 × 상품 호출 10번 ÷ 7,200초 × 1.5 = 4.2 RPS
// arrival-rate는 정수만 받아서 10초에 42번으로 넘긴다
export const TARGET_PER_10S = 42;

// 시드 재료 이름 중 자주 찾을 만한 것
const KEYWORDS = ['감자', '당근', '양파', '대파', '오이', '사과', '쌀', '소고기', '돼지고기', '삼겹살',
    '닭고기', '계란', '김치', '두부', '라면', '고추장', '된장', '우유', '식빵'];
const SORTS = ['SALES', 'PRICE_ASC', 'PRICE_DESC', 'LATEST'];

// 상세에 쓸 상품 코드는 판매량 상위 100개와 아무 페이지 900개를 섞어서, 캐시에 있는 상품과 없는 상품이 같이 불리게 함
export function setup() {
    const categories = http.get(`${BASE_URL}/api/categories`).json('data');
    const categoryIds = categories.flatMap((category) => category.children.map((child) => child.categoryId));

    const first = http.get(`${BASE_URL}/api/products?sort=SALES&size=100`).json('data');
    const codes = first.content.map((product) => product.productCode);
    for (let i = 0; i < 9; i++) {
        const page = Math.floor(Math.random() * first.totalPages);
        http.get(`${BASE_URL}/api/products?sort=LATEST&size=100&page=${page}`).json('data.content')
            .forEach((product) => codes.push(product.productCode));
    }
    if (first.totalElements < 90000) {
        throw new Error(`판매 중 상품이 ${first.totalElements}개뿐. 상품 10만 개 시드를 넣고 띄웠는지 확인`);
    }
    return { categoryIds, codes };
}

export function browse(data) {
    const pick = Math.random() * 10;
    if (pick < 4) {
        search(data);
    } else if (pick < 7) {
        get(`/api/products/${random(data.codes)}`, 'detail');
    } else if (pick < 9) {
        // 입력하는 중이라 앞 한 글자로 부름
        get(`/api/products/autocomplete?keyword=${encodeURIComponent(random(KEYWORDS).slice(0, 1))}`, 'autocomplete');
    } else {
        const [path, name] = random([['/api/products/popular', 'popular'],
            ['/api/products/popular-keywords', 'popularKeywords'], ['/api/categories', 'categories']]);
        get(path, name);
    }
}

// 반은 검색어로 찾고, 반은 카테고리로 둘러봄
function search(data) {
    const query = Math.random() < 0.5
        ? `keyword=${encodeURIComponent(random(KEYWORDS))}`
        : `categoryId=${random(data.categoryIds)}&inStock=${Math.random() < 0.3}`;
    get(`/api/products?${query}&sort=${random(SORTS)}&page=${Math.floor(Math.random() * 3)}`, 'search');
}

function get(path, name) {
    const res = http.get(`${BASE_URL}${path}`, { tags: { name, kind: 'product' } });
    check(res, { '200': (r) => r.status === 200 });
}

function random(list) {
    return list[Math.floor(Math.random() * list.length)];
}
