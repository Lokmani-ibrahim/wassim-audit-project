
package com.fintech.ledger;

import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import org.springframework.security.access.AccessDeniedException;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
class PayoutReservationService {

    private final LedgerProvider ledgerProvider;
    private final CustomerDebitAuthorization debitAuthorization;

    public PayoutReservationService(
            LedgerProvider ledgerProvider,
            CustomerDebitAuthorization debitAuthorization
    ) {
        this.ledgerProvider = Objects.requireNonNull(
                ledgerProvider
        );

        this.debitAuthorization = Objects.requireNonNull(
                debitAuthorization
        );
    }

    public LedgerOperationResult reserve(
            UUID userId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    ) {

        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(operationId, "operationId");

        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "amountMinor must be positive"
            );
        }

        String requiredOperationId = operationId.trim();

        if (requiredOperationId.isEmpty()) {
            throw new IllegalArgumentException(
                    "operationId must not be blank"
            );
        }

        // Mandatory authorization before reserving funds.
        // Requires KYC APPROVED and account ACTIVE.
        try {
            // Must precede the Formance reservation call.
            debitAuthorization.requireEligible(userId);
        } catch (AccessDeniedException denial) {
            throw new PayoutAuthorizationDeniedBeforePostingException(
                    denial
            );
        }

        // Reserve funds in Formance.
        //
        // users:{userId}:wallet:{currency}:available
        //                    ->
        // users:{userId}:wallet:{currency}:holds:{operationId}
        //
        // No bank payout is executed here.
        return ledgerProvider.reservePayout(
                userId,
                currency,
                amountMinor,
                requiredOperationId
        );
    }
}
