
package com.fintech.ledger.api;

import com.fintech.ledger.CustomerTransferNotificationRepairService;
import com.fintech.ledger.CustomerTransferNotificationRepairService.RepairBatch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/transfers/reconciliation")
@ConditionalOnProperty(
        prefix = "neos.transfers.reconciliation",
        name = {
                "admin-api-enabled",
                "notification-repair-enabled"
        },
        havingValue = "true"
)
@PreAuthorize("hasAuthority('ROLE_RECONCILIATION_ADMIN')")
public class CustomerTransferNotificationRepairAdminController {

    private final CustomerTransferNotificationRepairService
            repairService;

    public CustomerTransferNotificationRepairAdminController(
            CustomerTransferNotificationRepairService repairService
    ) {
        this.repairService =
                Objects.requireNonNull(repairService);
    }

    /**
     * Trigger a bounded, idempotent notification repair.
     *
     * This endpoint:
     *
     * - processes one sender's reconciled transfers
     * - checks existing notification outbox entries
     * - attempts to enqueue missing entries
     * - returns per-recipient repair outcomes
     *
     * It never:
     *
     * - sends a financial operation to Formance
     * - replays a transfer
     * - changes a transfer's financial status
     * - directly sends an email
     *
     * Repeated calls are safe because the underlying
     * notification service uses durable outbox keys.
     */
    @PostMapping("/{senderId}/notifications/repair")
    public RepairBatch repair(
            @PathVariable UUID senderId,

            @RequestParam(required = false)
            UUID afterRequestKey,

            @RequestParam(defaultValue = "25")
            int batchSize
    ) {

        if (batchSize < 1 || batchSize > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "batchSize must be between 1 and 100"
            );
        }

        return repairService.repairForSender(
                senderId,
                afterRequestKey,
                batchSize
        );
    }
}
