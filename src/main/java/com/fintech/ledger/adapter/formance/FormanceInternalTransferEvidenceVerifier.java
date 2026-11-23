
package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class FormanceInternalTransferEvidenceVerifier {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 10;

    private static final String REVERT_METADATA =
            "com.formance.spec/state/reverts";

    private final FormanceClient client;

    public FormanceInternalTransferEvidenceVerifier(
            FormanceClient client
    ) {
        this.client = Objects.requireNonNull(client);
    }

    public enum Finding {
        MATCHED,
        NOT_FOUND_UNCONFIRMED,
        INCONCLUSIVE
    }

    public record ExpectedTransfer(
            UUID senderId,
            UUID recipientId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    ) {
        public ExpectedTransfer {
            Objects.requireNonNull(senderId);
            Objects.requireNonNull(recipientId);
            Objects.requireNonNull(currency);
            Objects.requireNonNull(operationId);

            if (senderId.equals(recipientId)) {
                throw new IllegalArgumentException(
                        "Sender and recipient must differ"
                );
            }

            if (amountMinor <= 0) {
                throw new IllegalArgumentException(
                        "Amount must be positive"
                );
            }

            if (!operationId.matches(
                    "[A-Za-z0-9_-]{1,128}"
            )) {
                throw new IllegalArgumentException(
                        "Invalid operation ID"
                );
            }
        }
    }

    public record Result(
            Finding finding,
            Long transactionId,
            String explanation
    ) {
    }

    public Result inspect(ExpectedTransfer expected) {

        Objects.requireNonNull(expected);

        String reference =
                "internal_transfer:" + expected.operationId();

        String cursor = null;
        Set<String> seenCursors = new HashSet<>();

        Long matchedTransactionId = null;

        // Inspect all available pages, not just the first.
        for (int page = 0; page < MAX_PAGES; page++) {

            // Any Formance communication failure propagates.
            // It must never be interpreted as NOT_FOUND.
            FormanceTransactionsResponse response =
                    client.getTransactionsByReference(
                            reference,
                            PAGE_SIZE,
                            cursor
                    );

            if (response == null
                    || response.cursor() == null
                    || response.cursor().data() == null) {
                return inconclusive(
                        "Incomplete Formance lookup response"
                );
            }

            FormanceTransactionsCursor resultCursor =
                    response.cursor();

            for (FormanceTransactionData transaction :
                    resultCursor.data()) {

                if (transaction == null) {
                    return inconclusive(
                            "Null transaction in lookup"
                    );
                }

                // Don't trust that the remote filter worked
                // merely because we requested an exact match.
                if (!reference.equals(
                        transaction.reference()
                )) {
                    return inconclusive(
                            "Returned reference does not match"
                    );
                }

                if (!matchesExpectedPosting(
                        transaction,
                        expected
                )) {
                    return inconclusive(
                            "Transaction evidence conflicts "
                                    + "with expected transfer"
                    );
                }

                // Multiple transactions with the same
                // reference must not be treated as proof
                // of a single successful transfer.
                if (matchedTransactionId != null) {
                    return inconclusive(
                            "Multiple matching transactions"
                    );
                }

                matchedTransactionId = transaction.id();
            }

            if (!resultCursor.hasMore()) {

                if (matchedTransactionId == null) {
                    return new Result(
                            Finding.NOT_FOUND_UNCONFIRMED,
                            null,
                            "No matching transaction returned; "
                                    + "retry is NOT authorized"
                    );
                }

                return new Result(
                        Finding.MATCHED,
                        matchedTransactionId,
                        "One transaction matches expected "
                                + "reference, metadata and posting"
                );
            }

            String next = resultCursor.next();

            if (next == null
                    || next.isBlank()
                    || !seenCursors.add(next)) {

                return inconclusive(
                        "Missing or repeated pagination cursor"
                );
            }

            cursor = next;
        }

        return inconclusive(
                "Lookup exceeded maximum inspected pages"
        );
    }

    private boolean matchesExpectedPosting(
            FormanceTransactionData transaction,
            ExpectedTransfer expected
    ) {

        // A transaction ID of zero may be valid on
        // some Formance versions. Missing IDs are not.
        if (transaction.id() == null
                || transaction.id() < 0) {
            return false;
        }

        // Explicit false required. A missing
        // reverted field is inconclusive.
        if (!Boolean.FALSE.equals(
                transaction.reverted()
        )) {
            return false;
        }

        Map<String, String> metadata =
                transaction.metadata();

        if (metadata == null) {
            return false;
        }

        if (metadata.containsKey(REVERT_METADATA)) {
            return false;
        }

        if (!"internal_transfer".equals(
                metadata.get("neos.operation")
        )) {
            return false;
        }

        if (!expected.operationId().equals(
                metadata.get("neos.operation_id")
        )) {
            return false;
        }

        if (!expected.currency().currencyCode().equals(
                metadata.get("neos.currency")
        )) {
            return false;
        }

        // Internal transfers in the current adapter
        // have exactly one Formance posting.
        if (transaction.postings() == null
                || transaction.postings().size() != 1) {
            return false;
        }

        FormanceTransactionPosting posting =
                transaction.postings().get(0);

        if (posting == null) {
            return false;
        }

        String expectedSource =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                expected.senderId(),
                                expected.currency()
                        )
                        .value();

        String expectedDestination =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                expected.recipientId(),
                                expected.currency()
                        )
                        .value();

        return expectedSource.equals(posting.source())
                && expectedDestination.equals(
                posting.destination()
        )
                && expected.currency()
                .formanceAsset()
                .equals(posting.asset())
                && expected.amountMinor()
                == posting.amount();
    }

    private static Result inconclusive(String reason) {
        return new Result(
                Finding.INCONCLUSIVE,
                null,
                reason
        );
    }
}
