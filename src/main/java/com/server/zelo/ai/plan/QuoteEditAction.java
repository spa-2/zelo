package com.server.zelo.ai.plan;

public enum QuoteEditAction {
    REMOVE, // 견적의 상품을 뺀다 ("쌀 빼줘")
    CHANGE_QUANTITY, // 견적에 있는 상품의 개수를 정한 수로 바꾼다 ("김치 2개로 해줘")
    ADD // 재료를 더 담는다. 견적에 있는 재료면 그만큼 더한다 ("계란 2개 더", "햄 추가해 줘")
}
