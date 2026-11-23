
package com.fintech.ledger;

import com.fintech.identity.UserAccountStatusLookup;
import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import java.util.Objects;
import java.util.UUID;

@Service
public class CustomerTransferAdmissionService {

    private final JdbcTemplate jdbc;
    private final UserAccountStatusLookup accounts;
    private final CustomerDebitAuthorization debitAuthorization;

    public CustomerTransferAdmissionService(
            JdbcTemplate jdbc,
            UserAccountStatusLookup accounts,
            CustomerDebitAuthorization debitAuthorization
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.accounts = Objects.requireNonNull(accounts);
        this.debitAuthorization =
                Objects.requireNonNull(debitAuthorization);
    }

    /**
     * Atomically claims a new transfer and authorizes it.
     *
     * Returns:
     * true  -> new, authorized, committed claim
     * false -> request key already exists; caller must
     *          inspect the existing idempotency record
     *
     * Never contacts Formance.
     */

    private boolean claimExists(
            UUID senderId,
            UUID requestKey
    ) {

        Boolean exists = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM customer_transfer_idempotency
                    WHERE sender_user_id = ?
                      AND request_key = ?
                )
                """,
                Boolean.class,
                senderId,
                requestKey
        );

        return Boolean.TRUE.equals(exists);
    }

    @Transactional
    public boolean claimNew(
            UUID senderId,
            UUID recipientId,
            LedgerCurrency currency,
            long amountMinor,
            UUID requestKey,
            String operationId
    ) {

        Objects.requireNonNull(senderId);
        Objects.requireNonNull(recipientId);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(requestKey);
        Objects.requireNonNull(operationId);

        if (senderId.equals(recipientId)) {
            throw new IllegalArgumentException(
                    "Sender and recipient must differ"
            );
        }

        if (amountMinor <= 0 || operationId.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid transfer parameters"
            );
        }

        // 1. Preserve ordinary idempotency replay behavior.
        // No new financial operation is authorized here.
        if (claimExists(senderId, requestKey)) {
            return false;
        }

        // 2. Lock KYC FIRST.
        // Matches SumsubDecisionService's lock ordering.
        boolean approved =
                debitAuthorization.lockAndCheckApprovedForAdmission(
                        senderId
                );

        // 3. Another request may have committed while
        // we were waiting for the KYC lock.
        if (claimExists(senderId, requestKey)) {
            return false;
        }

        if (!approved) {
            throw new AccessDeniedException(
                    "Customer KYC is not approved"
            );
        }

        // 4. Lock the users row SECOND.
        // This serializes admission with account suspension.
        accounts.requireActiveForTransferAdmission(senderId);

        // 5. Preserve the existing eligibility check.
        // Both relevant rows are now locked.
        debitAuthorization.requireEligible(senderId);

        // 6. Create the claim AFTER authorization.
        // The entire operation is one short transaction.
        int inserted = jdbc.update(
                """
                INSERT INTO customer_transfer_idempotency (
                    sender_user_id,
                    request_key,
                    recipient_user_id,
                    currency,
                    amount_minor,
                    operation_id,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, 'IN_PROGRESS')
                ON CONFLICT (sender_user_id, request_key)
                DO NOTHING
                """,
                senderId,
                requestKey,
                recipientId,
                currency.name(),
                amountMinor,
                operationId
        );

        // A concurrent winner can still claim the key.
        // The caller will inspect the existing request.
        return inserted == 1;
    }


}
