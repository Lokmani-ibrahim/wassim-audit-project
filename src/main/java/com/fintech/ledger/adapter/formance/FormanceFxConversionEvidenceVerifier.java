
package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class FormanceFxConversionEvidenceVerifier {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 10;

    private static final String REVERT_METADATA =
            "com.formance.spec/state/reverts";

    private final FormanceClient client;

    public FormanceFxConversionEvidenceVerifier(FormanceClient client) {
        this.client = Objects.requireNonNull(client);
    }

    public enum Finding {
        MATCHED,
        NOT_FOUND_UNCONFIRMED,
        INCONCLUSIVE
    }

    public record Result(
            Finding finding,
            Long transactionId,
            String explanation
    ) {
    }

    /*
     * SECURITY CONTRACT
     *
     * The caller must construct this record from:
     * - persisted NEOS conversion parameters;
     * - authenticated sponsor-bank execution evidence;
     * - trusted internal bank configuration.
     *
     * Never populate it directly from a customer request.
     *
     * This verifier confirms Formance evidence only.
     * It does not independently confirm bank settlement.
     */
    public record ExpectedFxSettlement(
            UUID operationId,
            UUID userId,
            String bankId,
            LedgerCurrency sourceCurrency,
            LedgerCurrency destinationCurrency,
            long sourceMinor,
            long destinationMinor,
            BigDecimal bankRate,
            String bankQuoteId,
            String bankExecutionId
    ) {

        public ExpectedFxSettlement {

            Objects.requireNonNull(operationId);
            Objects.requireNonNull(userId);
            Objects.requireNonNull(sourceCurrency);
            Objects.requireNonNull(destinationCurrency);
            Objects.requireNonNull(bankRate);

            bankId = requireText(bankId, "bankId", 128);
            bankQuoteId = requireText(
                    bankQuoteId, "bankQuoteId", 128
            );
            bankExecutionId = requireText(
                    bankExecutionId, "bankExecutionId", 128
            );

            if (sourceCurrency == destinationCurrency) {
                throw new IllegalArgumentException(
                        "FX currencies must differ"
                );
            }

            if (sourceMinor <= 0 || destinationMinor <= 0) {
                throw new IllegalArgumentException(
                        "FX amounts must be positive"
                );
            }

            if (bankRate.signum() <= 0
                    || bankRate.precision() > 38
                    || bankRate.scale() > 18) {
                throw new IllegalArgumentException(
                        "Invalid bank exchange rate"
                );
            }

            // Reuse the canonical account factory to
            // enforce the bank account segment rules.
            LedgerAccountAddressFactory.bankMaster(
                    bankId,
                    sourceCurrency
            );
            LedgerAccountAddressFactory.bankMaster(
                    bankId,
                    destinationCurrency
            );
        }

        public String reference() {
            return "fx_convert:"
                    + operationId.toString().replace("-", "");
        }

        public String holdOperationId() {
            return "fx_"
                    + operationId.toString().replace("-", "");
        }

        public List<FormanceTransactionPosting> expectedPostings() {

            String sourceHold =
                    LedgerAccountAddressFactory.customerHold(
                            userId,
                            sourceCurrency,
                            holdOperationId()
                    ).value();

            String sourceControl =
                    LedgerAccountAddressFactory.safeguardedControl(
                            sourceCurrency
                    ).value();

            String sourceMaster =
                    LedgerAccountAddressFactory.bankMaster(
                            bankId,
                            sourceCurrency
                    ).value();

            String destinationMaster =
                    LedgerAccountAddressFactory.bankMaster(
                            bankId,
                            destinationCurrency
                    ).value();

            String destinationControl =
                    LedgerAccountAddressFactory.safeguardedControl(
                            destinationCurrency
                    ).value();

            String destinationAvailable =
                    LedgerAccountAddressFactory.customerAvailable(
                            userId,
                            destinationCurrency
                    ).value();

            return List.of(
                    new FormanceTransactionPosting(
                            sourceHold,
                            sourceControl,
                            sourceMinor,
                            sourceCurrency.formanceAsset()
                    ),
                    new FormanceTransactionPosting(
                            sourceMaster,
                            "world",
                            sourceMinor,
                            sourceCurrency.formanceAsset()
                    ),
                    new FormanceTransactionPosting(
                            "world",
                            destinationMaster,
                            destinationMinor,
                            destinationCurrency.formanceAsset()
                    ),
                    new FormanceTransactionPosting(
                            destinationControl,
                            destinationAvailable,
                            destinationMinor,
                            destinationCurrency.formanceAsset()
                    )
            );
        }

        public Map<String, String> expectedMetadata() {

            return Map.ofEntries(
                    Map.entry("neos.operation", "fx_convert"),
                    Map.entry(
                            "neos.operation_id",
                            operationId.toString()
                    ),
                    Map.entry(
                            "neos.user_id",
                            userId.toString()
                    ),
                    Map.entry("neos.bank_id", bankId),
                    Map.entry(
                            "neos.source_currency",
                            sourceCurrency.currencyCode()
                    ),
                    Map.entry(
                            "neos.destination_currency",
                            destinationCurrency.currencyCode()
                    ),
                    Map.entry(
                            "neos.source_minor",
                            Long.toString(sourceMinor)
                    ),
                    Map.entry(
                            "neos.destination_minor",
                            Long.toString(destinationMinor)
                    ),
                    Map.entry(
                            "neos.fx_provider",
                            "SPONSOR_BANK"
                    ),
                    Map.entry(
                            "neos.fx_provider_quote_id",
                            bankQuoteId
                    ),
                    Map.entry(
                            "neos.fx_bank_execution_id",
                            bankExecutionId
                    ),
                    Map.entry(
                            "neos.fx_hold_id",
                            holdOperationId()
                    )
            );
        }
    }

    public Result inspect(ExpectedFxSettlement expected) {

        Objects.requireNonNull(expected);

        String cursor = null;
        Set<String> seenCursors = new HashSet<>();

        Long matchingTransactionId = null;

        for (int page = 0; page < MAX_PAGES; page++) {

            /*
             * Formance communication failures propagate.
             * Never treat timeouts as missing evidence.
             */
            FormanceTransactionsResponse response =
                    client.getTransactionsByReference(
                            expected.reference(),
                            PAGE_SIZE,
                            cursor
                    );

            if (response == null
                    || response.cursor() == null
                    || response.cursor().data() == null) {

                return inconclusive(
                        "Incomplete Formance response"
                );
            }

            FormanceTransactionsCursor resultCursor =
                    response.cursor();

            for (FormanceTransactionData transaction
                    : resultCursor.data()) {

                if (transaction == null) {
                    return inconclusive(
                            "Null Formance transaction"
                    );
                }

                if (!expected.reference().equals(
                        transaction.reference()
                )) {
                    return inconclusive(
                            "Unexpected Formance reference"
                    );
                }

                if (!matches(transaction, expected)) {
                    return inconclusive(
                            "FX transaction evidence mismatch"
                    );
                }

                if (matchingTransactionId != null) {
                    return inconclusive(
                            "Multiple transactions share the FX reference"
                    );
                }

                matchingTransactionId = transaction.id();
            }

            if (!resultCursor.hasMore()) {

                if (matchingTransactionId == null) {
                    return new Result(
                            Finding.NOT_FOUND_UNCONFIRMED,
                            null,
                            "No matching ledger transaction; "
                                    + "posting retry is not authorized"
                    );
                }

                return new Result(
                        Finding.MATCHED,
                        matchingTransactionId,
                        "One non-reverted transaction contains "
                                + "the four expected FX postings"
                );
            }

            String next = resultCursor.next();

            if (next == null
                    || next.isBlank()
                    || !seenCursors.add(next)) {

                return inconclusive(
                        "Missing or repeated Formance cursor"
                );
            }

            cursor = next;
        }

        return inconclusive(
                "FX transaction lookup exceeded page limit"
        );
    }

    private static boolean matches(
            FormanceTransactionData transaction,
            ExpectedFxSettlement expected
    ) {

        // Consistent with the current V15 constraint.
        if (transaction.id() == null
                || transaction.id() <= 0) {
            return false;
        }

        if (!Boolean.FALSE.equals(transaction.reverted())) {
            return false;
        }

        Map<String, String> metadata = transaction.metadata();

        if (metadata == null
                || metadata.containsKey(REVERT_METADATA)) {
            return false;
        }

        for (Map.Entry<String, String> entry
                : expected.expectedMetadata().entrySet()) {

            if (!entry.getValue().equals(
                    metadata.get(entry.getKey())
            )) {
                return false;
            }
        }

        String recordedRate = metadata.get("neos.fx_rate");

        if (recordedRate == null) {
            return false;
        }

        try {
            if (new BigDecimal(recordedRate).compareTo(
                    expected.bankRate()
            ) != 0) {
                return false;
            }
        } catch (NumberFormatException exception) {
            return false;
        }

        List<FormanceTransactionPosting> actual =
                transaction.postings();

        List<FormanceTransactionPosting> required =
                expected.expectedPostings();

        if (actual == null
                || actual.size() != required.size()) {
            return false;
        }

        // Formance may return postings in a different
        // order. Match the complete posting multiset.
        List<FormanceTransactionPosting> remaining =
                new ArrayList<>(actual);

        for (FormanceTransactionPosting posting : required) {

            if (!remaining.remove(posting)) {
                return false;
            }
        }

        return remaining.isEmpty();
    }

    private static String requireText(
            String value,
            String name,
            int maxLength
    ) {

        Objects.requireNonNull(value, name);

        String normalized = value.trim();

        if (normalized.isEmpty()
                || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    "Invalid " + name
            );
        }

        return normalized;
    }

    private static Result inconclusive(String reason) {

        return new Result(
                Finding.INCONCLUSIVE,
                null,
                reason
        );
    }
}
