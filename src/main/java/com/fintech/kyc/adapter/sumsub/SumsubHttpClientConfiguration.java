
package com.fintech.kyc.adapter.sumsub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class SumsubHttpClientConfiguration {

    /**
     * Dedicated production HTTP client for Sumsub.
     *
     * Connect timeout defaults to 3000 milliseconds.
     * Read timeout defaults to 10000 milliseconds.
     *
     * Both can be overridden in application
     * configuration or environment variables.
     *
     * This bean does not modify Formance or
     * other external HTTP clients.
     */
    @Bean("sumsubRestClient")
    public RestClient sumsubRestClient(
            RestClient.Builder builder,
            SumsubProperties properties,

            @Value(
                    "${neos.kyc.sumsub.http.connect-timeout-ms:3000}"
            )
            int connectTimeoutMs,

            @Value(
                    "${neos.kyc.sumsub.http.read-timeout-ms:10000}"
            )
            int readTimeoutMs
    ) {

        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "Sumsub connect timeout must be positive"
            );
        }

        if (readTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "Sumsub read timeout must be positive"
            );
        }

        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(
                connectTimeoutMs
        );

        requestFactory.setReadTimeout(
                readTimeoutMs
        );

        return builder
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl())
                .build();
    }
}
