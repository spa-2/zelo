package com.server.zelo.domain.portone.dto.response;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOnePaymentResponse(
        String id,
        String status,
        Amount amount,
        OffsetDateTime paidAt
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(Long total) {
    }
}