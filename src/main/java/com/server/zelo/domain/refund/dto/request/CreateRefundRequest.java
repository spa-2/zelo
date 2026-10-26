package com.server.zelo.domain.refund.dto.request;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// items가 비어 있으면 남은 상품 전부를 환불한다
public record CreateRefundRequest(
        @NotBlank @Size(max = 50) String reason,
        @Valid List<RefundItemRequest> items
) {

    public Map<Long, Integer> toCancelQuantities() {
        if (items == null) {
            return Map.of();
        }
        return items.stream().collect(Collectors.toMap(
                RefundItemRequest::orderItemId,
                RefundItemRequest::quantity,
                (first, second) -> {
                    throw new BusinessException(ErrorCode.INVALID_INPUT, "한 요청에 같은 주문 상품을 두 번 넣을 수 없습니다.");
                }));
    }
}