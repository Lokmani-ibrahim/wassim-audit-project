package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.internal.taxonomy.LedgerAccountAddress;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class FormanceLedgerProvider implements LedgerProvider {

    private static final Pattern OPERATION_ID_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9_-]+$"
            );

    private static final int MAX_OPERATION_ID_LENGTH = 128;

    private final FormanceClient formanceClient;

    public FormanceLedgerProvider(
            FormanceClient formanceClient
    ) {
        this.formanceClient =
                Objects.requireNonNull(
                        formanceClient,
                        "formanceClient must not be null"
                );
    }

    @Override
    public void verifyConnection() {

        formanceClient.verifyConnection();
    }

    @Override
    public LedgerOperationResult transferBetweenUsers(
            UUID sourceUserId,
            UUID destinationUserId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    ) {

        Objects.requireNonNull(
                sourceUserId,
                "sourceUserId must not be null"
        );

        Objects.requireNonNull(
                destinationUserId,
                "destinationUserId must not be null"
        );

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        if (sourceUserId.equals(destinationUserId)) {
            throw new IllegalArgumentException(
                    "sourceUserId and destinationUserId must be different"
            );
        }

        requirePositiveAmount(
                amountMinor
        );

        String requiredOperationId =
                requireOperationId(
                        operationId
                );

        LedgerAccountAddress source =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                sourceUserId,
                                currency
                        );

        LedgerAccountAddress destination =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                destinationUserId,
                                currency
                        );

        return execute(
                "internal_transfer",
                requiredOperationId,
                currency,
                source,
                destination,
                amountMinor
        );
    }

    @Override
    public LedgerOperationResult reservePayout(
            UUID userId,
            LedgerCurrency currency,
            long amountMinor,
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

        requirePositiveAmount(
                amountMinor
        );

        String requiredOperationId =
                requireOperationId(
                        operationId
                );

        LedgerAccountAddress source =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                currency
                        );

        LedgerAccountAddress destination =
                LedgerAccountAddressFactory
                        .customerHold(
                                userId,
                                currency,
                                requiredOperationId
                        );

        return execute(
                "payout_reserve",
                requiredOperationId,
                currency,
                source,
                destination,
                amountMinor
        );
    }

    @Override
    public LedgerOperationResult releasePayout(
            UUID userId,
            LedgerCurrency currency,
            long amountMinor,
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

        requirePositiveAmount(
                amountMinor
        );

        String requiredOperationId =
                requireOperationId(
                        operationId
                );

        LedgerAccountAddress source =
                LedgerAccountAddressFactory
                        .customerHold(
                                userId,
                                currency,
                                requiredOperationId
                        );

        LedgerAccountAddress destination =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                currency
                        );

        return execute(
                "payout_release",
                requiredOperationId,
                currency,
                source,
                destination,
                amountMinor
        );
    }
    @Override
    public LedgerOperationResult settlePayout(
            UUID userId,
            String bankId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    ) {

        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        Objects.requireNonNull(
                bankId,
                "bankId must not be null"
        );

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        requirePositiveAmount(
                amountMinor
        );

        String requiredOperationId =
                requireOperationId(
                        operationId
                );

        LedgerAccountAddress bankMaster =
                LedgerAccountAddressFactory
                        .bankMaster(
                                bankId,
                                currency
                        );

        LedgerAccountAddress hold =
                LedgerAccountAddressFactory
                        .customerHold(
                                userId,
                                currency,
                                requiredOperationId
                        );

        LedgerAccountAddress safeguardedControl =
                LedgerAccountAddressFactory
                        .safeguardedControl(
                                currency
                        );

        String reference =
                "payout_settle:"
                        + requiredOperationId;

        String asset =
                currency.formanceAsset();

        String script =
                """
                send [%s %d] (
                  source = @%s
                  destination = @world
                )
    
                send [%s %d] (
                  source = @%s
                  destination = @%s
                )
                """
                        .formatted(
                                asset,
                                amountMinor,
                                bankMaster.value(),
                                asset,
                                amountMinor,
                                hold.value(),
                                safeguardedControl.value()
                        );

        FormanceCreateScriptTransactionRequest request =
                new FormanceCreateScriptTransactionRequest(
                        new FormanceScript(
                                script
                        ),
                        Map.of(
                                "neos.operation",
                                "payout_settle",
                                "neos.operation_id",
                                requiredOperationId,
                                "neos.bank_id",
                                bankId.trim(),
                                "neos.user_id",
                                userId.toString(),
                                "neos.currency",
                                currency.currencyCode()
                        ),
                        reference
                );

        FormanceTransactionResult result =
                formanceClient
                        .createScriptTransaction(
                                request,
                                reference
                        );

        return new LedgerOperationResult(
                result.transactionId(),
                result.reference()
        );
    }

    @Override
    public LedgerOperationResult collectFee(
            UUID userId,
            LedgerCurrency currency,
            long amountMinor,
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

        requirePositiveAmount(
                amountMinor
        );

        String requiredOperationId =
                requireOperationId(
                        operationId
                );

        LedgerAccountAddress source =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                currency
                        );

        LedgerAccountAddress destination =
                LedgerAccountAddressFactory
                        .platformFees(
                                currency
                        );

        return execute(
                "fee_collection",
                requiredOperationId,
                currency,
                source,
                destination,
                amountMinor
        );
    }

    @Override
    public LedgerOperationResult recordIncomingPayment(
            UUID userId,
            String bankId,
            LedgerCurrency currency,
            long amountMinor,
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

        requirePositiveAmount(
                amountMinor
        );

        String requiredOperationId =
                requireOperationId(
                        operationId
                );

        LedgerAccountAddress bankMaster =
                LedgerAccountAddressFactory
                        .bankMaster(
                                bankId,
                                currency
                        );

        LedgerAccountAddress destination =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                currency
                        );

        return recordIncoming(
                "incoming_payment",
                requiredOperationId,
                currency,
                amountMinor,
                bankMaster,
                destination,
                Map.of(
                        "neos.operation",
                        "incoming_payment",
                        "neos.operation_id",
                        requiredOperationId,
                        "neos.bank_id",
                        bankId.trim(),
                        "neos.user_id",
                        userId.toString(),
                        "neos.currency",
                        currency.currencyCode()
                )
        );
    }

    @Override
    public LedgerOperationResult recordUnattributedIncomingPayment(
            String bankId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    ) {

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        requirePositiveAmount(
                amountMinor
        );

        String requiredOperationId =
                requireOperationId(
                        operationId
                );

        LedgerAccountAddress bankMaster =
                LedgerAccountAddressFactory
                        .bankMaster(
                                bankId,
                                currency
                        );

        LedgerAccountAddress destination =
                LedgerAccountAddressFactory
                        .incomingSuspense(
                                currency
                        );

        return recordIncoming(
                "incoming_unattributed",
                requiredOperationId,
                currency,
                amountMinor,
                bankMaster,
                destination,
                Map.of(
                        "neos.operation",
                        "incoming_unattributed",
                        "neos.operation_id",
                        requiredOperationId,
                        "neos.bank_id",
                        bankId.trim(),
                        "neos.currency",
                        currency.currencyCode()
                )
        );
    }

    @Override
    public LedgerOperationResult attributeIncomingPayment(
            UUID userId,
            LedgerCurrency currency,
            long amountMinor,
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

        requirePositiveAmount(
                amountMinor
        );

        String requiredOperationId =
                requireOperationId(
                        operationId
                );

        LedgerAccountAddress source =
                LedgerAccountAddressFactory
                        .incomingSuspense(
                                currency
                        );

        LedgerAccountAddress destination =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                currency
                        );

        return execute(
                "incoming_attribution",
                requiredOperationId,
                currency,
                source,
                destination,
                amountMinor
        );
    }

    private LedgerOperationResult execute(
            String operationType,
            String operationId,
            LedgerCurrency currency,
            LedgerAccountAddress source,
            LedgerAccountAddress destination,
            long amountMinor
    ) {

        String reference =
                operationType
                        + ":"
                        + operationId;

        FormanceCreateTransactionRequest request =
                new FormanceCreateTransactionRequest(
                        List.of(
                                new FormancePosting(
                                        source.value(),
                                        destination.value(),
                                        currency.formanceAsset(),
                                        amountMinor
                                )
                        ),
                        Map.of(
                                "neos.operation",
                                operationType,
                                "neos.operation_id",
                                operationId,
                                "neos.currency",
                                currency.currencyCode()
                        ),
                        reference
                );

        FormanceTransactionResult result =
                formanceClient.createTransaction(
                        request,
                        reference
                );

        return new LedgerOperationResult(
                result.transactionId(),
                result.reference()
        );
    }

    private LedgerOperationResult recordIncoming(
            String operationType,
            String operationId,
            LedgerCurrency currency,
            long amountMinor,
            LedgerAccountAddress bankMaster,
            LedgerAccountAddress destination,
            Map<String, String> metadata
    ) {

        LedgerAccountAddress safeguardedControl =
                LedgerAccountAddressFactory
                        .safeguardedControl(
                                currency
                        );

        String reference =
                operationType
                        + ":"
                        + operationId;

        String asset =
                currency.formanceAsset();

        String script =
                """
                send [%s %d] (
                  source = @world
                  destination = @%s
                )

                send [%s %d] (
                  source = @%s allowing unbounded overdraft
                  destination = @%s
                )
                """
                        .formatted(
                                asset,
                                amountMinor,
                                bankMaster.value(),
                                asset,
                                amountMinor,
                                safeguardedControl.value(),
                                destination.value()
                        );

        FormanceCreateScriptTransactionRequest request =
                new FormanceCreateScriptTransactionRequest(
                        new FormanceScript(
                                script
                        ),
                        metadata,
                        reference
                );

        FormanceTransactionResult result =
                formanceClient
                        .createScriptTransaction(
                                request,
                                reference
                        );

        return new LedgerOperationResult(
                result.transactionId(),
                result.reference()
        );
    }

    private static void requirePositiveAmount(
            long amountMinor
    ) {

        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "amountMinor must be positive"
            );
        }
    }

    private static String requireOperationId(
            String operationId
    ) {

        Objects.requireNonNull(
                operationId,
                "operationId must not be null"
        );

        String trimmed =
                operationId.trim();

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    "operationId must not be blank"
            );
        }

        if (trimmed.length()
                > MAX_OPERATION_ID_LENGTH) {

            throw new IllegalArgumentException(
                    "operationId must not exceed "
                            + MAX_OPERATION_ID_LENGTH
                            + " characters"
            );
        }

        if (!OPERATION_ID_PATTERN
                .matcher(trimmed)
                .matches()) {

            throw new IllegalArgumentException(
                    "operationId may contain only letters, numbers, '_' and '-'"
            );
        }

        return trimmed;
    }
}