package com.fintech.ledger;

import com.fintech.ledger.adapter.formance.FormanceProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        FormanceProperties.class
)
public class LedgerConfiguration {

    @Bean
    @Qualifier("formanceRestClient")
    RestClient formanceRestClient(
            FormanceProperties properties
    ) {

        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(
                                properties.connectTimeout()
                        )
                        .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        httpClient
                );

        requestFactory.setReadTimeout(
                properties.readTimeout()
        );

        return RestClient.builder()
                .baseUrl(
                        properties.baseUrl().toString()
                )
                .requestFactory(requestFactory)
                .defaultHeader(
                        HttpHeaders.ACCEPT,
                        MediaType.APPLICATION_JSON_VALUE
                )
                .build();
    }
}