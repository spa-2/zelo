package com.server.zelo.domain.portone.dto.request;

public record PortOneBillingKeyRequest(
        String storeId,
        String billingKey,
        String orderName,
        Amount amount,
        String currency
) {

    private static final String KRW = "KRW";

    public record Amount(Long total) {
    }

    public static PortOneBillingKeyRequest of(String storeId, String billingKey, String orderName, Long amount) {
        return new PortOneBillingKeyRequest(storeId, billingKey, orderName, new Amount(amount), KRW);
    }
}