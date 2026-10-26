package com.server.zelo.ai.plan;

public enum MenuPlanIntent {
    SHOPPING_REQUEST, // 재료를 사 달라는 요청 (새 메뉴, 메뉴 변경, 인분 변경은 모두 새 견적)
    QUOTE_EDIT, // 지금 견적과 같은 메뉴에서 특정 상품만 빼거나 수량을 바꾸거나 더하는 요청
    GENERAL_QUESTION // 장보기가 아닌 말 (인사, 보관법 질문 등)
}
