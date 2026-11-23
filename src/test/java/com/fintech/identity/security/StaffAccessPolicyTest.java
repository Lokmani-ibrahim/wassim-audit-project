package com.fintech.identity.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffAccessPolicyTest {

    private static final String AUDIENCE = "neos-api";
    private static final String CLIENT = "neos-backoffice";
    private static final String ISSUER = "https://issuer.example/realms/neos";

    @Test
    void requiresConfigurationOnlyWhenEnabled() {
        new StaffAccessPolicy(false, "", "", "");
        assertThrows(IllegalStateException.class,
                () -> new StaffAccessPolicy(true, "", CLIENT, ISSUER));
        assertThrows(IllegalStateException.class,
                () -> new StaffAccessPolicy(true, AUDIENCE, "", ISSUER));
        assertThrows(IllegalStateException.class,
                () -> new StaffAccessPolicy(true, AUDIENCE, CLIENT, ""));
        assertThrows(IllegalStateException.class,
                () -> new StaffAccessPolicy(true, AUDIENCE, CLIENT, "relative-issuer"));
    }

    @Test
    void allowsDedicatedAdminOrComplianceAndDeniesMixedRoles() {
        StaffAccessPolicy policy = new StaffAccessPolicy(true, AUDIENCE, CLIENT, ISSUER);
        assertTrue(policy.canAccess(token(List.of("COMPLIANCE_OFFICER"), AUDIENCE, CLIENT)));
        assertTrue(policy.canAccess(token(List.of("ADMIN"), AUDIENCE, CLIENT)));
        for (List<String> roles : List.of(List.of("CUSTOMER"), List.of("OPERATIONS"),
                List.of("RECONCILIATION_ADMIN"), List.of("ADMIN", "CUSTOMER"),
                List.of("COMPLIANCE_OFFICER", "CUSTOMER"),
                List.of("ADMIN", "COMPLIANCE_OFFICER"))) {
            assertFalse(policy.canAccess(token(roles, AUDIENCE, CLIENT)));
        }
    }

    @Test
    void readsSupportedClientRolesAndRejectsCustomerFromAnotherClient() {
        StaffAccessPolicy policy = new StaffAccessPolicy(true, AUDIENCE, CLIENT, ISSUER);
        assertTrue(policy.canAccess(token(List.of(), List.of("ADMIN"), List.of(), AUDIENCE, CLIENT)));
        assertTrue(policy.canAccess(token(List.of(), List.of("COMPLIANCE_OFFICER"), List.of(), AUDIENCE, CLIENT)));
        assertFalse(policy.canAccess(token(List.of("ADMIN"), List.of(), List.of("CUSTOMER"), AUDIENCE, CLIENT)));
        assertFalse(policy.canAccess(token(List.of(), List.of("ADMIN", "CUSTOMER"), List.of(), AUDIENCE, CLIENT)));
        assertFalse(policy.canAccess(token(List.of("COMPLIANCE_OFFICER"), List.of("ADMIN"), List.of(), AUDIENCE, CLIENT)));
    }

    @Test
    void deniesWrongClientAudienceOrRoleAndDisabledFeature() {
        StaffAccessPolicy policy = new StaffAccessPolicy(true, AUDIENCE, CLIENT, ISSUER);
        assertFalse(policy.canAccess(token(List.of("COMPLIANCE_OFFICER"), "other-api", CLIENT)));
        assertFalse(policy.canAccess(token(List.of("COMPLIANCE_OFFICER"), AUDIENCE, "neos-mobile")));
        for (String role : List.of("CUSTOMER", "OPERATIONS", "RECONCILIATION_ADMIN")) {
            assertFalse(policy.canAccess(token(List.of(role), AUDIENCE, CLIENT)));
        }
        assertFalse(new StaffAccessPolicy(false, AUDIENCE, CLIENT, ISSUER)
                .canAccess(token(List.of("COMPLIANCE_OFFICER"), AUDIENCE, CLIENT)));
    }

    private JwtAuthenticationToken token(List<String> roles, String audience, String client) {
        return token(roles, List.of(), List.of(), audience, client);
    }

    private JwtAuthenticationToken token(List<String> realmRoles, List<String> clientRoles,
                                         List<String> otherClientRoles, String audience, String client) {
        Jwt jwt = new Jwt("test", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"), Map.of(
                "sub", "staff-id", "iss", ISSUER, "aud", List.of(audience), "azp", client,
                "realm_access", Map.of("roles", realmRoles),
                "resource_access", Map.of(CLIENT, Map.of("roles", clientRoles),
                        "neos-mobile", Map.of("roles", otherClientRoles))));
        return new JwtAuthenticationToken(jwt, new KeycloakRealmRoleConverter().convert(jwt));
    }
}
