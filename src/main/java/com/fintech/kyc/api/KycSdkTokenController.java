
package com.fintech.kyc.api;

import com.fintech.kyc.KycSdkTokenService;
import com.fintech.kyc.port.KycProvider;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/kyc")
public class KycSdkTokenController {

    private final KycSdkTokenService sdkTokenService;

    public KycSdkTokenController(
            KycSdkTokenService sdkTokenService
    ) {
        this.sdkTokenService =
                Objects.requireNonNull(sdkTokenService);
    }

    /**
     * Creates or renews a short-lived Sumsub SDK token.
     *
     * POST /api/kyc/sdk-token
     *
     * Authentication: Keycloak JWT, ROLE_CUSTOMER.
     *
     * No request body is required.
     * The identity comes exclusively from the JWT.
     */
    @PostMapping("/sdk-token")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<SdkTokenResponse> createSdkToken(
            @AuthenticationPrincipal Jwt jwt
    ) {

        KycProvider.VerificationSession session =
                sdkTokenService.createSession(
                        jwt.getSubject()
                );

        /*
         * SDK tokens are sensitive, short-lived credentials.
         * Do not allow HTTP caches to store this response.
         */
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(
                        new SdkTokenResponse(
                                session.token(),
                                session.expiresInSeconds()
                        )
                );
    }

    public record SdkTokenResponse(
            String token,
            long expiresInSeconds
    ) {
    }
}
