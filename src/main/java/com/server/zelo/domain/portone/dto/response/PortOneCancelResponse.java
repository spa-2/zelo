package com.server.zelo.domain.portone.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOneCancelResponse(Cancellation cancellation) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cancellation(String status) {
    }
}