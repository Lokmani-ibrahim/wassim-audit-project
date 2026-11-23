package com.fintech.ledger.internal.taxonomy;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class LedgerAccountAddressFactory {

    private static final Pattern SEGMENT_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9_-]+$"
            );

    private static final int MAX_SEGMENT_LENGTH = 128;

    private LedgerAccountAddressFactory() {
    }

    public static LedgerAccountAddress customerAvailable(
            UUID userId,
            LedgerCurrency currency
    ) {

        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        return new LedgerAccountAddress(
                "users:"
                        + userId
                        + ":wallet:"
                        + currency.currencyCode()
                        + ":available",
                currency,
                LedgerAccountKind.CUSTOMER_AVAILABLE
        );
    }

    public static LedgerAccountAddress customerHold(
            UUID userId,
            LedgerCurrency currency,
            String operationId
    ) {

        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        String requiredOperationId =
                requireSegment(
                        operationId,
                        "operationId"
                );

        return new LedgerAccountAddress(
                "users:"
                        + userId
                        + ":wallet:"
                        + currency.currencyCode()
                        + ":holds:"
                        + requiredOperationId,
                currency,
                LedgerAccountKind.CUSTOMER_HOLD
        );
    }

    public static LedgerAccountAddress bankMaster(
            String bankId,
            LedgerCurrency currency
    ) {

        String requiredBankId =
                requireSegment(
                        bankId,
                        "bankId"
                );

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        return new LedgerAccountAddress(
                "bank:"
                        + requiredBankId
                        + ":master:"
                        + currency.currencyCode(),
                currency,
                LedgerAccountKind.BANK_MASTER
        );
    }

    public static LedgerAccountAddress safeguardedControl(
            LedgerCurrency currency
    ) {

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        return new LedgerAccountAddress(
                "control:safeguarded:"
                        + currency.currencyCode(),
                currency,
                LedgerAccountKind.SAFEGUARDED_CONTROL
        );
    }

    public static LedgerAccountAddress incomingSuspense(
            LedgerCurrency currency
    ) {

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        return new LedgerAccountAddress(
                "suspense:incoming:"
                        + currency.currencyCode(),
                currency,
                LedgerAccountKind.INCOMING_SUSPENSE
        );
    }

    public static LedgerAccountAddress platformFees(
            LedgerCurrency currency
    ) {

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        return new LedgerAccountAddress(
                "platform:fees:"
                        + currency.currencyCode(),
                currency,
                LedgerAccountKind.PLATFORM_FEES
        );
    }

    private static String requireSegment(
            String value,
            String fieldName
    ) {

        Objects.requireNonNull(
                value,
                fieldName + " must not be null"
        );

        String trimmed =
                value.trim();

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }

        if (trimmed.length()
                > MAX_SEGMENT_LENGTH) {

            throw new IllegalArgumentException(
                    fieldName
                            + " must not exceed "
                            + MAX_SEGMENT_LENGTH
                            + " characters"
            );
        }

        if (!SEGMENT_PATTERN
                .matcher(trimmed)
                .matches()) {

            throw new IllegalArgumentException(
                    fieldName
                            + " may contain only letters, numbers, '_' and '-'"
            );
        }

        return trimmed;
    }
}