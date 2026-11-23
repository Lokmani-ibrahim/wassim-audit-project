
package com.fintech.ledger.api;

import com.fintech.ledger.CustomerTransferManualReconciliationService;
import com.fintech.ledger.CustomerTransferReconciliationInspectionService;
import com.fintech.ledger.port.LedgerOperationResult;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/transfers/reconciliation")
@ConditionalOnProperty(
        prefix = "neos.transfers.reconciliation",
        name = "admin-api-enabled",
        havingValue = "true"
)
@PreAuthorize("hasAuthority('ROLE_RECONCILIATION_ADMIN')")
public class CustomerTransferReconciliationAdminController {

    private static final Duration MIN_IN_PROGRESS_AGE =
            Duration.ofMinutes(15);

    private final CustomerTransferReconciliationInspectionService
            inspectionService;

    private final CustomerTransferManualReconciliationService
            reconciliationService;

    public CustomerTransferReconciliationAdminController(
            CustomerTransferReconciliationInspectionService
                    inspectionService,
            CustomerTransferManualReconciliationService
                    reconciliationService
    ) {
        this.inspectionService =
                Objects.requireNonNull(inspectionService);

        this.reconciliationService =
                Objects.requireNonNull(reconciliationService);
    }

    /*
     * Read-only inspection of unresolved transfers.
     *
     * UNKNOWN is always eligible for inspection.
     * IN_PROGRESS must be at least 15 minutes old.
     *
     * Formance is queried for evidence, but no
     * financial operation is submitted.
     */
    @GetMapping
    public List<CustomerTransferReconciliationInspectionService.Inspection>
    inspect(
            @RequestParam UUID senderId,
            @RequestParam(defaultValue = "20") int limit
    ) {

        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and 100"
            );
        }

        Instant staleBefore =
                Instant.now().minus(MIN_IN_PROGRESS_AGE);

        return inspectionService.inspectForSender(
                senderId,
                staleBefore,
                limit
        );
    }

    /*
     * Confirms a transfer only when the existing
     * reconciliation service obtains fresh,
     * matching evidence from Formance.
     *
     * The service provides:
     * - Reviewer authentication checks
     * - Positive evidence verification
     * - Database row locking
     * - Atomic SUCCEEDED transition
     * - Transactional reconciliation audit
     *
     * No Formance write occurs.
     */
    @PostMapping("/{senderId}/{requestKey}/confirm")
    public Confirmation confirm(
            @PathVariable UUID senderId,
            @PathVariable UUID requestKey
    ) {

        LedgerOperationResult result =
                reconciliationService.confirmPostedTransfer(
                        senderId,
                        requestKey
                );

        return new Confirmation(
                result.transactionId(),
                result.reference(),
                "SUCCEEDED"
        );
    }

    public record Confirmation(
            long transactionId,
            String reference,
            String status
    ) {
    }
}
