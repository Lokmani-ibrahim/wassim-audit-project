package com.fintech.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import org.springframework.security.oauth2.jwt.Jwt;

@Component
public class StaffAccessPolicy {

    private final boolean enabled;
    private final String audience;
    private final String clientId;
    private final String issuer;

    public StaffAccessPolicy(
            @Value("${neos.backoffice.enabled:false}") boolean enabled,
            @Value("${neos.backoffice.audience:}") String audience,
            @Value("${neos.backoffice.client-id:}") String clientId,
            @Value("${neos.backoffice.issuer:}") String issuer
    ) {
        if (enabled && (audience.isBlank() || clientId.isBlank() || issuer.isBlank())) {
            throw new IllegalStateException(
                    "Enabled backoffice requires audience, client-id and issuer"
            );
        }
        this.enabled = enabled;
        this.audience = audience;
        this.clientId = clientId;
        if (enabled) {
            URI parsed = URI.create(issuer);
            if (!parsed.isAbsolute() || parsed.getHost() == null
                    || parsed.getQuery() != null || parsed.getFragment() != null
                    || !("https".equals(parsed.getScheme()) || "http".equals(parsed.getScheme()))) {
                throw new IllegalStateException("Invalid backoffice issuer URI");
            }
        }
        this.issuer = issuer;
    }

    public boolean canAccess(Authentication authentication) {
        if (!enabled || !(authentication instanceof JwtAuthenticationToken jwtToken)
                || !authentication.isAuthenticated()) {
            return false;
        }
        Jwt jwt = jwtToken.getToken();
        Set<String> realmRoles = roles(jwt.getClaimAsMap("realm_access"));
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        Set<String> clientRoles = resourceAccess == null ? Set.of()
                : roles(resourceAccess.get(clientId));
        boolean customer = realmRoles.contains("CUSTOMER");
        if (resourceAccess != null) {
            customer |= resourceAccess.values().stream()
                    .anyMatch(value -> roles(value).contains("CUSTOMER"));
        }
        boolean admin = realmRoles.contains("ADMIN") || clientRoles.contains("ADMIN");
        boolean compliance = realmRoles.contains("COMPLIANCE_OFFICER")
                || clientRoles.contains("COMPLIANCE_OFFICER");
        if (customer || admin == compliance) {
            return false;
        }
        return jwt.getIssuer() != null
                && issuer.equals(jwtToken.getToken().getIssuer().toString())
                && jwtToken.getToken().getAudience().contains(audience)
                && clientId.equals(jwtToken.getToken().getClaimAsString("azp"));
    }

    private Set<String> roles(Object access) {
        if (!(access instanceof Map<?, ?> map)
                || !(map.get("roles") instanceof List<?> values)) {
            return Set.of();
        }
        Set<String> roles = new HashSet<>();
        values.stream().filter(String.class::isInstance)
                .map(String.class::cast).forEach(roles::add);
        return roles;
    }

    public List<String> capabilities(Authentication authentication) {
        if (!canAccess(authentication)) {
            throw new AccessDeniedException("Staff access denied");
        }
        return canReadCustomers(authentication) ? List.of("CUSTOMER_READ") : List.of();
    }

    public boolean canReadCustomers(Authentication authentication) {
        if (!canAccess(authentication)) return false;
        Jwt jwt = ((JwtAuthenticationToken) authentication).getToken();
        Set<String> realmRoles = roles(jwt.getClaimAsMap("realm_access"));
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        Set<String> clientRoles = resourceAccess == null ? Set.of()
                : roles(resourceAccess.get(clientId));
        return realmRoles.contains("COMPLIANCE_OFFICER")
                || clientRoles.contains("COMPLIANCE_OFFICER");
    }
}
