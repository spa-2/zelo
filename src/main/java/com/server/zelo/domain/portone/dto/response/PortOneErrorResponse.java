package com.server.zelo.domain.portone.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOneErrorResponse(
        String type,
        String message
) {
}