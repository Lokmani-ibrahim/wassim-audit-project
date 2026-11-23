package com.fintech.kyc.adapter.sumsub;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "neos.kyc.sumsub")
public record SumsubProperties(
        String baseUrl,
        String appToken,
        String secretKey,
        String levelName,
        long sdkTokenTtlSeconds
) {
}