
package com.fintech.ledger.api;

import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.CustomerTransferIdempotencyService;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/transfers/internal")
@ConditionalOnProperty(
        prefix = "neos.transfers",
        name = "customer-api-enabled",
        havingValue = "true"
)
public class CustomerInternalTransferController {

    private final IdentityLookupService identityLookupService;
    private final CustomerDebitAuthorization authorization;
    private final CustomerTransferIdempotencyService idempotencyService;

    public CustomerInternalTransferController(
            IdentityLookupService identityLookupService,
            CustomerDebitAuthorization authorization,
            CustomerTransferIdempotencyService idempotencyService
    ) {
        this.identityLookupService =
                Objects.requireNonNull(identityLookupService);

        this.authorization =
                Objects.requireNonNull(authorization);

        this.idempotencyService =
                Objects.requireNonNull(idempotencyService);
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<CustomerInternalTransferResponse> transfer(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CustomerInternalTransferRequest request
    ) {

        // Never trust a client-supplied sender identity.
        // Resolve the NEOS user from the authenticated
        // Keycloak subject.
        UUID sourceUserId = identityLookupService
                .findUserIdByKeycloakSubject(jwt.getSubject())
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "Customer profile not found"
                        )
                );

        UUID destinationUserId = request.destinationUserId();

        if (sourceUserId.equals(destinationUserId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cannot transfer to the same customer"
            );
        }

        // Conservative internal-transfer policy:
        // recipient must also have approved KYC
        // and an active account.
        authorization.requireEligible(destinationUserId);

        // Scope the idempotency identifier to the
        // authenticated sender.
        //
        // Same sender + same request key
        // => same downstream operation identifier.
        String operationId =
                "ct_"
                        + sourceUserId.toString().replace("-", "")
                        + "_"
                        + request.idempotencyKey()
                        .toString().replace("-", "");

        // InternalTransferService independently enforces
        // sender eligibility before calling Formance.
        LedgerOperationResult result = idempotencyService.execute(
                sourceUserId,
                destinationUserId,
                request.currency(),
                request.amountMinor(),
                request.idempotencyKey(),
                operationId
        );

        return ResponseEntity.ok(
                new CustomerInternalTransferResponse(
                        result.transactionId(),
                        result.reference()
                )
        );
    }
}
