package com.fintech.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            StaffAccessPolicy staffAccessPolicy
    ) throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authorizeHttpRequests(authorize ->
                        authorize
                                .requestMatchers(
                                        "/actuator/health"
                                ).permitAll()

                                // Sumsub authenticates with an HMAC,
                                // not a Keycloak JWT.
                                .requestMatchers(
                                        HttpMethod.POST,
                                        "/api/webhooks/sumsub"
                                ).permitAll()

                                .requestMatchers(HttpMethod.GET, "/api/backoffice/me")
                                .access((authentication, context) ->
                                        new AuthorizationDecision(
                                                staffAccessPolicy.canAccess(authentication.get())
                                        )
                                )

                                .requestMatchers(HttpMethod.GET,
                                        "/api/backoffice/customers",
                                        "/api/backoffice/customers/*",
                                        "/api/backoffice/customers/*/wallets",
                                        "/api/backoffice/customers/*/transactions")
                                .access((authentication, context) ->
                                        new AuthorizationDecision(
                                                staffAccessPolicy.canReadCustomers(authentication.get())
                                        )
                                )
                                .requestMatchers("/api/backoffice/customers",
                                        "/api/backoffice/customers/**").denyAll()

                                // All other API routes still require JWT.
                                .anyRequest().authenticated()
                )

                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt ->
                                jwt.jwtAuthenticationConverter(
                                        jwtAuthenticationConverter()
                                )
                        )
                );

        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {

        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(
                new KeycloakRealmRoleConverter()
        );

        return converter;
    }
}
