
package com.fintech.ledger;

import com.fintech.identity.UserAccountStatusLookup;
import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PayoutRequestAdmissionService {

    private static final Pattern BANK_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final JdbcTemplate jdbc;
    private final UserAccountStatusLookup accounts;
    private final CustomerDebitAuthorization authorization;

    public PayoutRequestAdmissionService(
            JdbcTemplate jdbc,
            UserAccountStatusLookup accounts,
            CustomerDebitAuthorization authorization
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.accounts = Objects.requireNonNull(accounts);
        this.authorization = Objects.requireNonNull(authorization);
    }

    public record AdmissionResult(
            UUID payoutId,
            String operationId,
            boolean created
    ) {
    }

    private record ExistingPayout(
            UUID payoutId,
            String operationId,
            String currency,
            long amountMinor,
            String settlementBankId
    ) {
    }

    private List<ExistingPayout> findExisting(
            UUID userId,
            UUID requestKey
    ) {
        return jdbc.query(
                """
                SELECT payout_id,
                       operation_id,
                       currency,
                       amount_minor,
                       settlement_bank_id
                FROM payout_requests
                WHERE user_id = ?
                  AND request_key = ?
                """,
                (rs, rowNum) -> new ExistingPayout(
                        rs.getObject("payout_id", UUID.class),
                        rs.getString("operation_id"),
                        rs.getString("currency"),
                        rs.getLong("amount_minor"),
                        rs.getString("settlement_bank_id")
                ),
                userId,
                requestKey
        );
    }

    private AdmissionResult inspectExisting(
            ExistingPayout existing,
            LedgerCurrency currency,
            long amountMinor,
            String bankId,
            String operationId
    ) {

        boolean identical =
                existing.operationId().equals(operationId)
                        && existing.currency().equals(currency.name())
                        && existing.amountMinor() == amountMinor
                        && Objects.equals(
                        existing.settlementBankId(),
                        bankId
                );

        if (!identical) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payout idempotency key was already used "
                            + "with different request parameters"
            );
        }

        // This is only an admission lookup.
        // The execution coordinator must inspect the
        // persisted payout status before taking action.
        return new AdmissionResult(
                existing.payoutId(),
                existing.operationId(),
                false
        );
    }

    private static String requireBankId(String bankId) {

        Objects.requireNonNull(bankId, "bankId");

        String normalized = bankId.trim();

        if (!BANK_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Invalid internal settlement bank identifier"
            );
        }

        return normalized;
    }

    private static String operationId(
            UUID userId,
            UUID requestKey
    ) {
        // Stable, unique for the combination of
        // user + idempotency key.
        // Compatible with Formance operation-id rules.
        return "po_"
                + userId.toString().replace("-", "")
                + "_"
                + requestKey.toString().replace("-", "");
    }

    /**
     * Creates an authorized payout request.
     *
     * No Formance operation is performed.
     *
     * created=true:
     *   A new REQUESTED record was committed.
     *
     * created=false:
     *   The same request already existed.
     *   Do not interpret this as permission to
     *   submit or resubmit a Formance operation.
     */
    @Transactional
    public AdmissionResult admit(
            UUID userId,
            UUID requestKey,
            LedgerCurrency currency,
            long amountMinor,
            String settlementBankId
    ) {

        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(currency, "currency");

        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "Payout amount must be positive"
            );
        }

        String bankId = requireBankId(settlementBankId);
        String requiredOperationId = operationId(
                userId,
                requestKey
        );

        // 1. Existing requests do not initiate
        // a new financial operation.
        List<ExistingPayout> existing =
                findExisting(userId, requestKey);

        if (!existing.isEmpty()) {
            return inspectExisting(
                    existing.get(0),
                    currency,
                    amountMinor,
                    bankId,
                    requiredOperationId
            );
        }

        // 2. KYC lock FIRST.
        // Same order as SumsubDecisionService and
        // CustomerTransferAdmissionService.
        boolean approved =
                authorization.lockAndCheckApprovedForAdmission(
                        userId
                );

        // 3. The request might have been committed
        // while we were waiting for the KYC lock.
        existing = findExisting(userId, requestKey);

        if (!existing.isEmpty()) {
            return inspectExisting(
                    existing.get(0),
                    currency,
                    amountMinor,
                    bankId,
                    requiredOperationId
            );
        }

        if (!approved) {
            throw new AccessDeniedException(
                    "Customer KYC is not approved for payout"
            );
        }

        // 4. Account lock SECOND.
        // Serializes new payout admission with
        // account suspension.
        accounts.requireActiveForTransferAdmission(userId);

        // 5. Preserve existing eligibility rules.
        // The KYC and users rows remain locked.
        authorization.requireEligible(userId);

        UUID payoutId = UUID.randomUUID();

        // 6. Committed claim WITHOUT a Formance call.
        int inserted = jdbc.update(
                """
                INSERT INTO payout_requests (
                    payout_id,
                    user_id,
                    request_key,
                    operation_id,
                    currency,
                    amount_minor,
                    settlement_bank_id,
                    status
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, 'REQUESTED'
                )
                ON CONFLICT (user_id, request_key)
                DO NOTHING
                """,
                payoutId,
                userId,
                requestKey,
                requiredOperationId,
                currency.name(),
                amountMinor,
                bankId
        );

        if (inserted == 1) {
            return new AdmissionResult(
                    payoutId,
                    requiredOperationId,
                    true
            );
        }

        // Defensive handling of a concurrent winner.
        existing = findExisting(userId, requestKey);

        if (existing.isEmpty()) {
            throw new IllegalStateException(
                    "Payout claim conflict without "
                            + "a visible persisted request"
            );
        }

        return inspectExisting(
                existing.get(0),
                currency,
                amountMinor,
                bankId,
                requiredOperationId
        );
    }
}
