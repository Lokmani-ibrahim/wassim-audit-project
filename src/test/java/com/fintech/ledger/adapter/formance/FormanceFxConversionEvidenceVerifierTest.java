
package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.LedgerProviderUnavailableException;
import com.fintech.ledger.adapter.formance
        .FormanceFxConversionEvidenceVerifier.ExpectedFxSettlement;
import com.fintech.ledger.adapter.formance
        .FormanceFxConversionEvidenceVerifier.Finding;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FormanceFxConversionEvidenceVerifierTest {

    @Mock
    private FormanceClient client;

    private FormanceFxConversionEvidenceVerifier verifier;

    private ExpectedFxSettlement expected;

    @BeforeEach
    void setUp() {

        verifier = new FormanceFxConversionEvidenceVerifier(
                client
        );

        expected = new ExpectedFxSettlement(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "sponsor",
                LedgerCurrency.EUR,
                LedgerCurrency.USD,
                10_000L,
                11_000L,
                new BigDecimal("1.10"),
                "bank-quote-123",
                "bank-execution-456"
        );
    }

    @Test
    void verifiesAllFourCanonicalPostings() {

        onePage(List.of(validTransaction()));

        var result = verifier.inspect(expected);

        assertThat(result.finding())
                .isEqualTo(Finding.MATCHED);

        assertThat(result.transactionId())
                .isEqualTo(12345L);
    }

    @Test
    void acceptsDifferentPostingOrder() {

        List<FormanceTransactionPosting> reordered =
                new ArrayList<>(expected.expectedPostings());

        Collections.reverse(reordered);

        onePage(List.of(transaction(
                reordered,
                metadata(),
                false
        )));

        assertThat(verifier.inspect(expected).finding())
                .isEqualTo(Finding.MATCHED);
    }

    @Test
    void rejectsMissingBankMasterPosting() {

        List<FormanceTransactionPosting> incomplete =
                new ArrayList<>(expected.expectedPostings());

        incomplete.remove(2);

        onePage(List.of(transaction(
                incomplete,
                metadata(),
                false
        )));

        assertThat(verifier.inspect(expected).finding())
                .isEqualTo(Finding.INCONCLUSIVE);
    }

    @Test
    void rejectsIncorrectDestinationAsset() {

        List<FormanceTransactionPosting> postings =
                new ArrayList<>(expected.expectedPostings());

        FormanceTransactionPosting original =
                postings.get(3);

        postings.set(
                3,
                new FormanceTransactionPosting(
                        original.source(),
                        original.destination(),
                        original.amount(),
                        "EUR/2"
                )
        );

        onePage(List.of(transaction(
                postings,
                metadata(),
                false
        )));

        assertThat(verifier.inspect(expected).finding())
                .isEqualTo(Finding.INCONCLUSIVE);
    }

    @Test
    void rejectsIncorrectBankExecutionReference() {

        Map<String, String> altered = metadata();

        altered.put(
                "neos.fx_bank_execution_id",
                "different-bank-execution"
        );

        onePage(List.of(transaction(
                expected.expectedPostings(),
                altered,
                false
        )));

        assertThat(verifier.inspect(expected).finding())
                .isEqualTo(Finding.INCONCLUSIVE);
    }

    @Test
    void rejectsDifferentBankMasterAccount() {

        List<FormanceTransactionPosting> postings =
                new ArrayList<>(expected.expectedPostings());

        FormanceTransactionPosting original =
                postings.get(1);

        postings.set(
                1,
                new FormanceTransactionPosting(
                        "bank:another_bank:master:EUR",
                        original.destination(),
                        original.amount(),
                        original.asset()
                )
        );

        onePage(List.of(transaction(
                postings,
                metadata(),
                false
        )));

        assertThat(verifier.inspect(expected).finding())
                .isEqualTo(Finding.INCONCLUSIVE);
    }

    @Test
    void rejectsRevertedTransaction() {

        onePage(List.of(transaction(
                expected.expectedPostings(),
                metadata(),
                true
        )));

        assertThat(verifier.inspect(expected).finding())
                .isEqualTo(Finding.INCONCLUSIVE);
    }

    @Test
    void rejectsMissingReversalStatus() {

        onePage(List.of(transaction(
                expected.expectedPostings(),
                metadata(),
                null
        )));

        assertThat(verifier.inspect(expected).finding())
                .isEqualTo(Finding.INCONCLUSIVE);
    }

    @Test
    void rejectsDuplicateTransactions() {

        onePage(List.of(
                validTransaction(),
                validTransaction()
        ));

        assertThat(verifier.inspect(expected).finding())
                .isEqualTo(Finding.INCONCLUSIVE);
    }

    @Test
    void missingTransactionDoesNotAuthorizeRetry() {

        onePage(List.of());

        var result = verifier.inspect(expected);

        assertThat(result.finding())
                .isEqualTo(Finding.NOT_FOUND_UNCONFIRMED);

        assertThat(result.transactionId()).isNull();
    }

    @Test
    void rejectsUnexpectedTransactionReference() {

        FormanceTransactionData valid = validTransaction();

        FormanceTransactionData altered =
                new FormanceTransactionData(
                        valid.postings(),
                        valid.metadata(),
                        valid.timestamp(),
                        "fx_convert:incorrect",
                        valid.id(),
                        valid.insertedAt(),
                        valid.updatedAt(),
                        valid.reverted()
                );

        onePage(List.of(altered));

        assertThat(verifier.inspect(expected).finding())
                .isEqualTo(Finding.INCONCLUSIVE);
    }

    @Test
    void formanceFailureIsNotInterpretedAsNotFound() {

        when(client.getTransactionsByReference(
                eq(expected.reference()),
                eq(50),
                isNull()
        )).thenThrow(
                new LedgerProviderUnavailableException(
                        "Formance unavailable"
                )
        );

        assertThatThrownBy(() -> verifier.inspect(expected))
                .isInstanceOf(
                        LedgerProviderUnavailableException.class
                );
    }

    private void onePage(
            List<FormanceTransactionData> transactions
    ) {

        FormanceTransactionsResponse response =
                mock(FormanceTransactionsResponse.class);

        FormanceTransactionsCursor cursor =
                mock(FormanceTransactionsCursor.class);

        when(client.getTransactionsByReference(
                eq(expected.reference()),
                eq(50),
                isNull()
        )).thenReturn(response);

        when(response.cursor()).thenReturn(cursor);
        when(cursor.data()).thenReturn(transactions);

        lenient()
                .when(cursor.hasMore())
                .thenReturn(false);
    }

    private FormanceTransactionData validTransaction() {

        return transaction(
                expected.expectedPostings(),
                metadata(),
                false
        );
    }

    private FormanceTransactionData transaction(
            List<FormanceTransactionPosting> postings,
            Map<String, String> metadata,
            Boolean reverted
    ) {

        Instant now = Instant.now();

        return new FormanceTransactionData(
                postings,
                metadata,
                now,
                expected.reference(),
                12345L,
                now,
                now,
                reverted
        );
    }

    private Map<String, String> metadata() {

        Map<String, String> values =
                new HashMap<>(expected.expectedMetadata());

        values.put(
                "neos.fx_rate",
                expected.bankRate().toPlainString()
        );

        return values;
    }
}
