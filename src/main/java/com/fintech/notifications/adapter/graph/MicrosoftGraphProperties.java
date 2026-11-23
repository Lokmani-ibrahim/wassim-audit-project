package com.fintech.notifications.adapter.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties(
        prefix = "neos.notifications.microsoft-graph"
)
public record MicrosoftGraphProperties(

        boolean enabled,
        String tenantId,
        String clientId,
        String clientSecret,
        String sender,
        URI graphBaseUrl

) {

    public MicrosoftGraphProperties {

        if (graphBaseUrl == null) {
            graphBaseUrl =
                    URI.create(
                            "https://graph.microsoft.com/v1.0"
                    );
        }
    }
}