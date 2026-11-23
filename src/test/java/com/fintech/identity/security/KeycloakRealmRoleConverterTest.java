package com.fintech.identity.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter =
            new KeycloakRealmRoleConverter();

    @Test
    void shouldConvertKeycloakRealmRolesToSpringAuthorities() {

        Jwt jwt = new Jwt(
                "test-token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of(
                        "sub", "user-123",
                        "realm_access", Map.of(
                                "roles", List.of(
                                        "CUSTOMER",
                                        "OPERATIONS"
                                )
                        )
                )
        );

        Collection<GrantedAuthority> authorities =
                converter.convert(jwt);

        assertTrue(
                authorities.stream()
                        .anyMatch(authority ->
                                authority.getAuthority()
                                        .equals("ROLE_CUSTOMER")
                        )
        );

        assertTrue(
                authorities.stream()
                        .anyMatch(authority ->
                                authority.getAuthority()
                                        .equals("ROLE_OPERATIONS")
                        )
        );
    }

    @Test
    void shouldReturnEmptyAuthoritiesWhenRealmAccessIsMissing() {

        Jwt jwt = new Jwt(
                "test-token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "user-123")
        );

        Collection<GrantedAuthority> authorities =
                converter.convert(jwt);

        assertTrue(authorities.isEmpty());
    }
}