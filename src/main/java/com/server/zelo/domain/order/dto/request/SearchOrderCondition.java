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
}
