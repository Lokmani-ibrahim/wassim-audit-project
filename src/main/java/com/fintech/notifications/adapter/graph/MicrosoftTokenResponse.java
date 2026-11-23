package com.fintech.notifications.adapter.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(
        ignoreUnknown = true
)
public record MicrosoftTokenResponse(

        @JsonProperty("token_type")
        String tokenType,

        @JsonProperty("expires_in")
        long expiresIn,

        @JsonProperty("access_token")
        String accessToken

) {
}