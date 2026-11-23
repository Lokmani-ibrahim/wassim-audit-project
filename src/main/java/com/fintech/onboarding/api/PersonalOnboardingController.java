package com.fintech.onboarding.api;

import com.fintech.onboarding.PersonalOnboardingResult;
import com.fintech.onboarding.PersonalOnboardingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/onboarding")
public class PersonalOnboardingController {

    private final PersonalOnboardingService personalOnboardingService;

    public PersonalOnboardingController(
            PersonalOnboardingService personalOnboardingService
    ) {
        this.personalOnboardingService =
                personalOnboardingService;
    }

    @PostMapping("/personal")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<PersonalOnboardingResponse> onboardPersonal(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PersonalOnboardingRequest request
    ) {

        Object emailClaim = jwt.getClaims().get("email");
        String email = emailClaim instanceof String value ? value : null;
        PersonalOnboardingResult result =
                personalOnboardingService.onboard(
                        jwt.getSubject(),
                        email,
                        request.phoneNumber(),
                        request.installationId(),
                        request.platform(),
                        request.termsVersion()
                );

        return ResponseEntity.ok(
                PersonalOnboardingResponse.from(result)
        );
    }
}
