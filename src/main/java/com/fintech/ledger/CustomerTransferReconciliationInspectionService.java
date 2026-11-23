
package com.fintech.ledger;

import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier;
import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier.ExpectedTransfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class CustomerTransferReconciliationInspectionService {

    private static final Logger log = LoggerFactory.getLogger(
            CustomerTransferReconciliationInspectionService.class
    );

    private final CustomerTransferReconciliationQueryService queryService;
    private final FormanceInternalTransferEvidenceVerifier verifier;

    public CustomerTransferReconciliationInspectionService(
            CustomerTransferReconciliationQueryService queryService,
            FormanceInternalTransferEvidenceVerifier verifier
    ) {
        this.queryService = Objects.requireNonNull(queryService);
        this.verifier = Objects.requireNonNull(verifier);
    }

    public enum Finding {
        MATCHED,
        NOT_FOUND_UNCONFIRMED,
        INCONCLUSIVE,
        LOOKUP_FAILED
    }

    public record Inspection(
            UUID senderUserId,
            UUID requestKey,
            String operationId,
            CustomerTransferReconciliationQueryService.UnresolvedStatus storedStatus,
            Finding finding,
            Long formanceTransactionId
    ) {
    }

    /**
     * Inspects unresolved transfers for a specific customer.
     *
     * This method is intentionally NOT @Transactional.
     *
     * PostgreSQL queries complete before external Formance
     * inspection begins.
     *
     * No ledger writes or database status changes occur.
     */
    public List<Inspection> inspectForSender(
            UUID senderId,
            Instant staleBefore,
            int limit
    ) {

        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(staleBefore, "staleBefore");

        List<CustomerTransferReconciliationQueryService.UnresolvedTransfer>
                unresolved = queryService.findForSender(
                senderId,
                staleBefore,
                limit
        );

        List<Inspection> inspections = new ArrayList<>();

        for (var transfer : unresolved) {
            inspections.add(inspectOne(transfer));
        }

        return List.copyOf(inspections);
    }

    private Inspection inspectOne(
            CustomerTransferReconciliationQueryService.UnresolvedTransfer transfer
    ) {

        ExpectedTransfer expected = new ExpectedTransfer(
                transfer.senderUserId(),
                transfer.recipientUserId(),
                transfer.currency(),
                transfer.amountMinor(),
                transfer.operationId()
        );

        try {

            var result = verifier.inspect(expected);

            if (result == null || result.finding() == null) {
                return inspection(
                        transfer,
                        Finding.INCONCLUSIVE,
                        null
                );
            }

            Finding finding = switch (result.finding()) {
                case MATCHED -> Finding.MATCHED;
                case NOT_FOUND_UNCONFIRMED ->
                        Finding.NOT_FOUND_UNCONFIRMED;
                case INCONCLUSIVE -> Finding.INCONCLUSIVE;
            };

            // A claimed match without a valid transaction ID
            // cannot count as confirmed evidence.
            if (finding == Finding.MATCHED
                    && (result.transactionId() == null
                    || result.transactionId() < 0)) {

                return inspection(
                        transfer,
                        Finding.INCONCLUSIVE,
                        null
                );
            }

            return inspection(
                    transfer,
                    finding,
                    finding == Finding.MATCHED
                            ? result.transactionId()
                            : null
            );

        } catch (RuntimeException exception) {

            // An unavailable Formance service is not
            // evidence that a transfer was never executed.
            log.warn(
                    "Formance reconciliation inspection failed "
                            + "for operation {}",
                    transfer.operationId(),
                    exception
            );

            return inspection(
                    transfer,
                    Finding.LOOKUP_FAILED,
                    null
            );
        }
    }

    private static Inspection inspection(
            CustomerTransferReconciliationQueryService.UnresolvedTransfer transfer,
            Finding finding,
            Long transactionId
    ) {

        return new Inspection(
                transfer.senderUserId(),
                transfer.requestKey(),
                transfer.operationId(),
                transfer.status(),
                finding,
                transactionId
        );
    }
}
