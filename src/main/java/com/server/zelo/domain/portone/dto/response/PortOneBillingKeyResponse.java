package com.server.zelo.domain.portone.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOneBillingKeyResponse(
        String status,
        String billingKey,
        List<Method> methods,
        Customer customer
) {

    // type은 BillingKeyPaymentMethodCard 등이다. 카드면 card가 채워진다
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Method(String type, Card card) {
    }

    // number는 포트원이 마스킹해서 준다
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Card(String name, String number) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(String id) {
    }
}