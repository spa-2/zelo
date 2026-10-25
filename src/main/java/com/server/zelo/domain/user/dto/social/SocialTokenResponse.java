package com.server.zelo.domain.user.dto.social;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SocialTokenResponse(
        @JsonProperty("access_token")
        String accessToken
) {
}
