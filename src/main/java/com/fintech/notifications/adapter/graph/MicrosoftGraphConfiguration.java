package com.fintech.notifications.adapter.graph;

import com.fintech.notifications.port.EmailNotificationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration(
        proxyBeanMethods = false
)
@EnableConfigurationProperties(
        MicrosoftGraphProperties.class
)
@ConditionalOnProperty(
        prefix = "neos.notifications.microsoft-graph",
        name = "enabled",
        havingValue = "true"
)
public class MicrosoftGraphConfiguration {

    @Bean
    RestClient microsoftIdentityRestClient() {

        return RestClient
                .builder()
                .baseUrl(
                        "https://login.microsoftonline.com"
                )
                .build();
    }

    @Bean
    RestClient microsoftGraphRestClient(
            MicrosoftGraphProperties properties
    ) {

        return RestClient
                .builder()
                .baseUrl(
                        properties
                                .graphBaseUrl()
                                .toString()
                )
                .build();
    }

    @Bean
    MicrosoftGraphAccessTokenProvider
    microsoftGraphAccessTokenProvider(
            @Qualifier("microsoftIdentityRestClient")
            RestClient microsoftIdentityRestClient,
            MicrosoftGraphProperties properties
    ) {

        return new MicrosoftGraphAccessTokenProvider(
                microsoftIdentityRestClient,
                properties
        );
    }

    @Bean
    EmailNotificationPort microsoftGraphEmailAdapter(
            @Qualifier("microsoftGraphRestClient")
            RestClient microsoftGraphRestClient,
            MicrosoftGraphAccessTokenProvider tokenProvider,
            MicrosoftGraphProperties properties
    ) {

        return new MicrosoftGraphEmailAdapter(
                microsoftGraphRestClient,
                tokenProvider,
                properties
        );
    }
}