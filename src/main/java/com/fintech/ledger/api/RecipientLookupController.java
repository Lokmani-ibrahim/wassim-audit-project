package com.fintech.ledger.api;

import com.fintech.ledger.RecipientLookupService;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

/**
 * POST so the email is never part of a URL
 * (access logs, proxies, browser history).
 *
 * Only exposed together with the customer
 * internal-transfer API it serves.
 */
@RestController
@RequestMapping("/api/recipients")
@ConditionalOnProperty(
        prefix = "neos.transfers",
        name = "customer-api-enabled",
        havingValue = "true"
)
public class RecipientLookupController {

    private final RecipientLookupService recipientLookupService;

    public RecipientLookupController(
            RecipientLookupService recipientLookupService
    ) {
        this.recipientLookupService =
                Objects.requireNonNull(recipientLookupService);
    }

    @PostMapping("/lookup")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<RecipientLookupResponse> lookup(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RecipientLookupRequest request
    ) {

        return ResponseEntity.ok(
                RecipientLookupResponse.from(
                        recipientLookupService.lookup(
                                jwt.getSubject(),
                                request.email()
                        )
                )
        );
    }
}
