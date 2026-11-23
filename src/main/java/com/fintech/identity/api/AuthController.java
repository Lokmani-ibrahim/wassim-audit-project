package com.fintech.identity.api;

import com.fintech.identity.IdentityRegistrationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final IdentityRegistrationService identityRegistrationService;

    public AuthController(IdentityRegistrationService identityRegistrationService) {
        this.identityRegistrationService = identityRegistrationService;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, Object> me(
            @AuthenticationPrincipal Jwt jwt
    ) {

        String subject = jwt.getSubject();
        Object emailClaim = jwt.getClaims().get("email");
        String email = emailClaim instanceof String value ? value : null;
        IdentityRegistrationService.requireIdentity(subject, email);
        Object usernameClaim = jwt.getClaims().get("preferred_username");
        String username = usernameClaim instanceof String value && !value.isBlank() ? value : email;
        return Map.of(
                "subject", subject,
                "username", username,
                "email",
                email,
                "onboardingEligibility",
                identityRegistrationService.eligibility(subject, email).name()
        );
    }
}
