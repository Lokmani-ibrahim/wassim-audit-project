package com.fintech.onboarding.api;

import com.fintech.onboarding.CustomerProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class CustomerProfileController {

    private final CustomerProfileService customerProfileService;

    public CustomerProfileController(
            CustomerProfileService customerProfileService
    ) {
        this.customerProfileService =
                customerProfileService;
    }

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<CustomerProfileResponse> profile(
            @AuthenticationPrincipal Jwt jwt
    ) {

        return ResponseEntity.ok(
                CustomerProfileResponse.from(
                        customerProfileService.getProfile(
                                jwt.getSubject()
                        )
                )
        );
    }
}
