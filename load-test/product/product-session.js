import {
    autocomplete, getCategories, getDetail, getPopular, getPopularKeywords, loadProductData, random, randomProductCode, search,
} from './product-api.js';

// product-smoke.js, product-load.js, product-stress.js, product-limit.js가 같이 쓰는 상품 조회 흐름
// 요청 1번 = 반복 1번. 사용자 1명이 상품을 10번 부른다고 보고 목록·검색 4, 상세 3, 자동완성 2, 인기 상품·인기 검색어·카테고리 1 비율로 고른다
// 요청 하나하나는 product-api.js에 있다 (다른 도메인·전체 시나리오도 거기서 가져다 쓴다)

// 목표: DAU 5만 명 중 피크 1시간에 1만 명 × 상품 호출 10번 ÷ 3,600초 × 1.5 = 41.7 RPS
// arrival-rate는 정수만 받아서 10초에 417번으로 넘긴다
export const TARGET_PER_10S = 417;

// 판매량 상위 100개는 setup에서 받고, 나머지 상세는 LT- 상품 코드 중 아무거나 골라 캐시에 없는 상품도 부른다
export function setup() {
    return loadProductData();
}

export function browse(data) {
    const pick = Math.random() * 10;
    if (pick < 4) {
        search(data, { page: Math.floor(Math.random() * 3) });
    } else if (pick < 7) {
        getDetail(randomProductCode(data));
    } else if (pick < 9) {
        autocomplete();
    } else {
        random([getPopular, getPopularKeywords, getCategories])();
    }
}
