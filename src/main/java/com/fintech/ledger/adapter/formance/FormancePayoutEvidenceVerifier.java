
package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class FormancePayoutEvidenceVerifier {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 10;

    private static final String REVERT_METADATA =
            "com.formance.spec/state/reverts";

    private final FormanceClient client;

    public FormancePayoutEvidenceVerifier(
            FormanceClient client
    ) {
        this.client = Objects.requireNonNull(client);
    }

    public enum Action {

        RESERVE("payout_reserve"),
        SETTLE("payout_settle"),
        RELEASE("payout_release");

        private final String operationType;

        Action(String operationType) {
            this.operationType = operationType;
        }

        public String operationType() {
            return operationType;
        }
    }

    public enum Finding {
        MATCHED,
        NOT_FOUND_UNCONFIRMED,
        INCONCLUSIVE
    }

    public record ExpectedPayout(
            Action action,
            UUID userId,
            String bankId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    ) {

        public ExpectedPayout {

            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(operationId, "operationId");

            if (amountMinor <= 0) {
                throw new IllegalArgumentException(
                        "Payout amount must be positive"
                );
            }

            if (!operationId.matches(
                    "[A-Za-z0-9_-]{1,128}"
            )) {
                throw new IllegalArgumentException(
                        "Invalid payout operation ID"
                );
            }

            if (action == Action.SETTLE) {

                if (bankId == null
                        || !bankId.matches(
                        "[A-Za-z0-9_-]{1,128}"
                )) {

                    throw new IllegalArgumentException(
                            "Settlement requires a valid bank ID"
                    );
                }
            }
        }

        public String reference() {
            return action.operationType()
                    + ":"
                    + operationId;
        }
    }

    public record Result(
            Finding finding,
            Long transactionId,
            String explanation
    ) {
    }

    /**
     * Performs read-only Formance inspection.
     *
     * MATCHED is the only result that can support
     * an audited PostgreSQL confirmation.
     *
     * NOT_FOUND_UNCONFIRMED does not authorize retry.
     *
     * No financial posting is ever performed here.
     */
    public Result inspect(ExpectedPayout expected) {

        Objects.requireNonNull(expected, "expected");

        String reference = expected.reference();

        String cursor = null;
        Set<String> seenCursors = new HashSet<>();

        Long matchedTransactionId = null;

        for (int page = 0; page < MAX_PAGES; page++) {

            // Communication exceptions propagate.
            // Never translate a failed lookup into NOT_FOUND.
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

            for (FormanceTransactionData transaction
                    : resultCursor.data()) {

                if (transaction == null) {
                    return inconclusive(
                            "Null transaction in lookup"
                    );
                }

                // Verify the reference ourselves.
                if (!reference.equals(
                        transaction.reference()
                )) {
                    return inconclusive(
                            "Unexpected transaction reference"
                    );
                }

                if (!matchesExpectedTransaction(
                        transaction,
                        expected
                )) {
                    return inconclusive(
                            "Transaction evidence conflicts "
                                    + "with expected payout"
                    );
                }

                if (matchedTransactionId != null) {
                    return inconclusive(
                            "Multiple transactions share "
                                    + "the payout reference"
                    );
                }

                matchedTransactionId = transaction.id();
            }

            if (!resultCursor.hasMore()) {

                if (matchedTransactionId == null) {

                    return new Result(
                            Finding.NOT_FOUND_UNCONFIRMED,
                            null,
                            "No matching transaction found; "
                                    + "retry is NOT authorized"
                    );
                }

                return new Result(
                        Finding.MATCHED,
                        matchedTransactionId,
                        "Exactly one transaction matches "
                                + "the expected payout evidence"
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

    private boolean matchesExpectedTransaction(
            FormanceTransactionData transaction,
            ExpectedPayout expected
    ) {

        if (transaction.id() == null
                || transaction.id() < 0) {
            return false;
        }

        // A missing reverted flag is not sufficient proof.
        if (!Boolean.FALSE.equals(
                transaction.reverted()
        )) {
            return false;
        }

        Map<String, String> metadata =
                transaction.metadata();

        if (metadata == null
                || metadata.containsKey(REVERT_METADATA)) {
            return false;
        }

        if (!expected.action()
                .operationType()
                .equals(metadata.get("neos.operation"))) {
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

        // The settlement adapter includes these
        // additional transaction metadata fields.
        if (expected.action() == Action.SETTLE) {

            if (!expected.userId().toString().equals(
                    metadata.get("neos.user_id")
            )) {
                return false;
            }

            if (!expected.bankId().equals(
                    metadata.get("neos.bank_id")
            )) {
                return false;
            }
        }

        List<FormanceTransactionPosting> postings =
                transaction.postings();

        if (postings == null) {
            return false;
        }

        return switch (expected.action()) {

            case RESERVE ->
                    matchesReserve(postings, expected);

            case SETTLE ->
                    matchesSettlement(postings, expected);

            case RELEASE ->
                    matchesRelease(postings, expected);
        };
    }

    private boolean matchesReserve(
            List<FormanceTransactionPosting> postings,
            ExpectedPayout expected
    ) {

        if (postings.size() != 1) {
            return false;
        }

        return matchesPosting(
                postings.get(0),
                available(expected),
                hold(expected),
                expected
        );
    }

    private boolean matchesRelease(
            List<FormanceTransactionPosting> postings,
            ExpectedPayout expected
    ) {

        if (postings.size() != 1) {
            return false;
        }

        return matchesPosting(
                postings.get(0),
                hold(expected),
                available(expected),
                expected
        );
    }

    private boolean matchesSettlement(
            List<FormanceTransactionPosting> postings,
            ExpectedPayout expected
    ) {

        // Both movements must occur in the SAME
        // Formance transaction. No missing or
        // additional postings are accepted.
        if (postings.size() != 2) {
            return false;
        }

        FormanceTransactionPosting first =
                postings.get(0);

        FormanceTransactionPosting second =
                postings.get(1);

        String bankMaster =
                LedgerAccountAddressFactory.bankMaster(
                        expected.bankId(),
                        expected.currency()
                ).value();

        String safeguarded =
                LedgerAccountAddressFactory.safeguardedControl(
                        expected.currency()
                ).value();

        // Formance @world resolves to the world account.
        String world = "world";

        boolean bankFirst =
                matchesPosting(
                        first,
                        bankMaster,
                        world,
                        expected
                )
                        && matchesPosting(
                        second,
                        hold(expected),
                        safeguarded,
                        expected
                );

        boolean holdFirst =
                matchesPosting(
                        first,
                        hold(expected),
                        safeguarded,
                        expected
                )
                        && matchesPosting(
                        second,
                        bankMaster,
                        world,
                        expected
                );

        return bankFirst || holdFirst;
    }

    private boolean matchesPosting(
            FormanceTransactionPosting posting,
            String expectedSource,
            String expectedDestination,
            ExpectedPayout expected
    ) {

        if (posting == null) {
            return false;
        }

        return expectedSource.equals(posting.source())
                && expectedDestination.equals(
                posting.destination()
        )
                && expected.currency()
                .formanceAsset()
                .equals(posting.asset())
                && expected.amountMinor() == posting.amount();
    }

    private static String available(
            ExpectedPayout expected
    ) {

        return LedgerAccountAddressFactory.customerAvailable(
                expected.userId(),
                expected.currency()
        ).value();
    }

    private static String hold(
            ExpectedPayout expected
    ) {

        return LedgerAccountAddressFactory.customerHold(
                expected.userId(),
                expected.currency(),
                expected.operationId()
        ).value();
    }

    private static Result inconclusive(String reason) {

        return new Result(
                Finding.INCONCLUSIVE,
                null,
                reason
        );
    }
}
