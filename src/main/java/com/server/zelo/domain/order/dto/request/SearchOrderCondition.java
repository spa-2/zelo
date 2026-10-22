package com.server.zelo.domain.order.dto.request;

import com.server.zelo.domain.order.entity.OrderStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

public record SearchOrderCondition(
        OrderStatus status,
        @PositiveOrZero
        Integer page,
        @Min(1)
        @Max(100)
        Integer size
) {
        private static final int DEFAULT_PAGE = 0;
        private static final int DEFAULT_SIZE = 20;

        // 쿼리 파라미터가 없으면 ORDER-02 명세의 기본값(page 0, size 20)을 씀.
        public SearchOrderCondition {
                page = page == null ? DEFAULT_PAGE : page;
                size = size == null ? DEFAULT_SIZE : size;
        }
}
