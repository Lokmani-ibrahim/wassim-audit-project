package com.fintech.kyc.api;

import com.fintech.kyc.KycStartResult;
import com.fintech.kyc.KycStartService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kyc")
public class KycController {

    private final KycStartService kycStartService;

    public KycController(
            KycStartService kycStartService
    ) {
        this.kycStartService = kycStartService;
    }

    @PostMapping("/start")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<KycStartResponse> startKyc(
            @AuthenticationPrincipal Jwt jwt
    ) {

        KycStartResult result =
                kycStartService.start(jwt.getSubject());

        return ResponseEntity.ok(
                KycStartResponse.from(result)
        );
    }
}