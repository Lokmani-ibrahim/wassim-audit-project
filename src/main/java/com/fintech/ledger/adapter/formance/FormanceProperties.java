package com.fintech.ledger.adapter.formance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(
        prefix = "neos.ledger.formance"
)
public record FormanceProperties(

        @NotNull
        URI baseUrl,

        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_-]+$")
        String ledgerName,

        @NotBlank
        @Pattern(regexp = "^v[0-9]+\\.[0-9]+\\.[0-9]+$")
        String schemaVersion,

        @NotNull
        Duration connectTimeout,

        @NotNull
        Duration readTimeout
) {
}