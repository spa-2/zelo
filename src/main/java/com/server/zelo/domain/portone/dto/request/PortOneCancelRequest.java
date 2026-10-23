package com.server.zelo.domain.portone.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortOneCancelRequest (
        String storeId,
        String reason,
        Long amount,
        Long currentCancellableAmount
) {

    public static PortOneCancelRequest of(String storeId, String reason) {
        return new PortOneCancelRequest(storeId, reason, null, null);
    }

    public static PortOneCancelRequest of(String storeId, String reason, Long amount, Long currentCancellableAmount) {
        return new PortOneCancelRequest(storeId, reason, amount, currentCancellableAmount);
    }

}
