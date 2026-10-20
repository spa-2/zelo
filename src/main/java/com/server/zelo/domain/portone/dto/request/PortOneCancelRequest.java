package com.server.zelo.domain.portone.dto.request;

public record PortOneCancelRequest (
        String storeId,
        String reason) {

    public static PortOneCancelRequest of(String storeId, String reason) {
        return new PortOneCancelRequest(storeId, reason);
    }

}
