package com.fintech.ledger.api;

import com.fintech.ledger.SyntheticCreditService;
import com.fintech.ledger.port.LedgerOperationResult;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping(
        "/api/backoffice/synthetic-credits"
)
@ConditionalOnProperty(
        prefix = "neos.backoffice.synthetic-credit",
        name = "enabled",
        havingValue = "true"
)
public class SyntheticCreditController {

    private final SyntheticCreditService syntheticCreditService;

    public SyntheticCreditController(
            SyntheticCreditService syntheticCreditService
    ) {

        this.syntheticCreditService =
                Objects.requireNonNull(
                        syntheticCreditService,
                        "syntheticCreditService must not be null"
                );
    }

    @PostMapping
    @PreAuthorize(
            "hasRole('COMPLIANCE_OFFICER')"
    )
    public ResponseEntity<SyntheticCreditResponse> credit(
            @Valid
            @RequestBody
            SyntheticCreditRequest request
    ) {

        LedgerOperationResult result =
                syntheticCreditService.credit(
                        request.userId(),
                        request.currency(),
                        request.amountMinor(),
                        request.operationId()
                );

        SyntheticCreditResponse response =
                new SyntheticCreditResponse(
                        request.userId(),
                        request.currency(),
                        request.amountMinor(),
                        result.transactionId(),
                        result.reference()
                );

        return ResponseEntity
                .ok(
                        response
                );
    }
}