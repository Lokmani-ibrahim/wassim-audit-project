
package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.fintech.ledger.adapter.formance.FormancePayoutEvidenceVerifier.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FormancePayoutEvidenceVerifierTest {

    private FormanceClient client;
    private FormancePayoutEvidenceVerifier verifier;

    private UUID userId;

    private static final LedgerCurrency EUR =
            LedgerCurrency.EUR;

    private static final long AMOUNT = 5_000L;

    private static final String OPERATION_ID =
            "po_reconciliation_test_001";

    @BeforeEach
    void setup() {

        client = mock(FormanceClient.class);

        verifier = new FormancePayoutEvidenceVerifier(
                client
        );

        userId = UUID.randomUUID();
    }

    private ExpectedPayout expected(Action action) {

        return new ExpectedPayout(
                action,
                userId,
                "sponsor",
                EUR,
                AMOUNT,
                OPERATION_ID
        );
    }

    private String available() {

        return LedgerAccountAddressFactory.customerAvailable(
                userId,
                EUR
        ).value();
    }

    private String hold() {

        return LedgerAccountAddressFactory.customerHold(
                userId,
                EUR,
                OPERATION_ID
        ).value();
    }

    private String bank() {

        return LedgerAccountAddressFactory.bankMaster(
                "sponsor",
                EUR
        ).value();
    }

    private String safeguarded() {

        return LedgerAccountAddressFactory.safeguardedControl(
                EUR
        ).value();
    }

    private FormanceTransactionPosting posting(
            String source,
            String destination,
            long amount
    ) {

        FormanceTransactionPosting posting =
                mock(FormanceTransactionPosting.class);

        when(posting.source()).thenReturn(source);
        when(posting.destination()).thenReturn(destination);
        when(posting.asset()).thenReturn(
                EUR.formanceAsset()
        );
        when(posting.amount()).thenReturn(amount);

        return posting;
    }

    private FormanceTransactionData transaction(
            ExpectedPayout expected,
            List<FormanceTransactionPosting> postings
    ) {

        FormanceTransactionData tx =
                mock(FormanceTransactionData.class);

        Map<String, String> metadata = new HashMap<>();

        metadata.put(
                "neos.operation",
                expected.action().operationType()
        );

        metadata.put(
                "neos.operation_id",
                expected.operationId()
        );

        metadata.put(
                "neos.currency",
                expected.currency().currencyCode()
        );

        if (expected.action() == Action.SETTLE) {

            metadata.put(
                    "neos.bank_id",
                    expected.bankId()
            );

            metadata.put(
                    "neos.user_id",
                    expected.userId().toString()
            );
        }

        when(tx.id()).thenReturn(900L);
        when(tx.reference()).thenReturn(
                expected.reference()
        );
        when(tx.metadata()).thenReturn(metadata);
        when(tx.reverted()).thenReturn(false);
        when(tx.postings()).thenReturn(postings);

        return tx;
    }

    private void respond(
            ExpectedPayout expected,
            List<FormanceTransactionData> transactions
    ) {

        FormanceTransactionsCursor cursor =
                mock(FormanceTransactionsCursor.class);

        when(cursor.data()).thenReturn(transactions);
        when(cursor.hasMore()).thenReturn(false);

        FormanceTransactionsResponse response =
                mock(FormanceTransactionsResponse.class);

        when(response.cursor()).thenReturn(cursor);

        when(client.getTransactionsByReference(
                expected.reference(),
                50,
                null
        )).thenReturn(response);
    }

    @Test
    void exactReservationMatches() {

        ExpectedPayout expected = expected(Action.RESERVE);

        respond(
                expected,
                List.of(transaction(
                        expected,
                        List.of(posting(
                                available(),
                                hold(),
                                AMOUNT
                        ))
                ))
        );

        Result result = verifier.inspect(expected);

        assertEquals(Finding.MATCHED, result.finding());
        assertEquals(900L, result.transactionId());
    }

    @Test
    void exactSettlementMatchesBothPostingsInAnyOrder() {

        ExpectedPayout expected = expected(Action.SETTLE);

        respond(
                expected,
                List.of(transaction(
                        expected,
                        List.of(
                                // Reverse the order used in
                                // the Formance script.
                                posting(
                                        hold(),
                                        safeguarded(),
                                        AMOUNT
                                ),
                                posting(
                                        bank(),
                                        "world",
                                        AMOUNT
                                )
                        )
                ))
        );

        Result result = verifier.inspect(expected);

        assertEquals(Finding.MATCHED, result.finding());
        assertEquals(900L, result.transactionId());
    }

    @Test
    void exactReleaseMatches() {

        ExpectedPayout expected = expected(Action.RELEASE);

        respond(
                expected,
                List.of(transaction(
                        expected,
                        List.of(posting(
                                hold(),
                                available(),
                                AMOUNT
                        ))
                ))
        );

        Result result = verifier.inspect(expected);

        assertEquals(Finding.MATCHED, result.finding());
    }

    @Test
    void settlementWithOnlyOnePostingIsInconclusive() {

        ExpectedPayout expected = expected(Action.SETTLE);

        respond(
                expected,
                List.of(transaction(
                        expected,
                        List.of(posting(
                                bank(),
                                "world",
                                AMOUNT
                        ))
                ))
        );

        Result result = verifier.inspect(expected);

        assertEquals(
                Finding.INCONCLUSIVE,
                result.finding()
        );
    }

    @Test
    void settlementWithIncorrectHoldAmountIsInconclusive() {

        ExpectedPayout expected = expected(Action.SETTLE);

        respond(
                expected,
                List.of(transaction(
                        expected,
                        List.of(
                                posting(
                                        bank(),
                                        "world",
                                        AMOUNT
                                ),
                                posting(
                                        hold(),
                                        safeguarded(),
                                        4_000L
                                )
                        )
                ))
        );

        Result result = verifier.inspect(expected);

        assertEquals(
                Finding.INCONCLUSIVE,
                result.finding()
        );
    }

    @Test
    void revertedReservationIsInconclusive() {

        ExpectedPayout expected = expected(Action.RESERVE);

        FormanceTransactionData tx = transaction(
                expected,
                List.of(posting(
                        available(),
                        hold(),
                        AMOUNT
                ))
        );

        when(tx.reverted()).thenReturn(true);

        respond(expected, List.of(tx));

        assertEquals(
                Finding.INCONCLUSIVE,
                verifier.inspect(expected).finding()
        );
    }

    @Test
    void duplicateTransactionsWithSameReferenceAreInconclusive() {

        ExpectedPayout expected = expected(Action.RELEASE);

        FormanceTransactionData tx = transaction(
                expected,
                List.of(posting(
                        hold(),
                        available(),
                        AMOUNT
                ))
        );

        respond(expected, List.of(tx, tx));

        assertEquals(
                Finding.INCONCLUSIVE,
                verifier.inspect(expected).finding()
        );
    }

    @Test
    void missingTransactionNeverAuthorizesRetry() {

        ExpectedPayout expected = expected(Action.SETTLE);

        respond(expected, List.of());

        Result result = verifier.inspect(expected);

        assertEquals(
                Finding.NOT_FOUND_UNCONFIRMED,
                result.finding()
        );

        assertNull(result.transactionId());
    }

    @Test
    void lookupFailureIsNotTreatedAsNotFound() {

        ExpectedPayout expected = expected(Action.SETTLE);

        when(client.getTransactionsByReference(
                expected.reference(),
                50,
                null
        )).thenThrow(
                new IllegalStateException(
                        "Formance unavailable"
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> verifier.inspect(expected)
        );
    }
}
