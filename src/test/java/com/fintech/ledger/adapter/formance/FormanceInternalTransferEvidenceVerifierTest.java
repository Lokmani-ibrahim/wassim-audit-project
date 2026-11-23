
package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FormanceInternalTransferEvidenceVerifierTest {

    private static final String OPERATION_ID =
            "ct_test_001";

    private static final String REFERENCE =
            "internal_transfer:" + OPERATION_ID;

    private final UUID senderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();

    private FormanceClient client;
    private FormanceInternalTransferEvidenceVerifier verifier;

    private FormanceInternalTransferEvidenceVerifier
            .ExpectedTransfer expected;

    @BeforeEach
    void setup() {

        client = mock(FormanceClient.class);

        verifier = new FormanceInternalTransferEvidenceVerifier(
                client
        );

        expected =
                new FormanceInternalTransferEvidenceVerifier
                        .ExpectedTransfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        OPERATION_ID
                );
    }

    private Map<String, String> metadata() {
        return Map.of(
                "neos.operation", "internal_transfer",
                "neos.operation_id", OPERATION_ID,
                "neos.currency", "EUR"
        );
    }

    private String source() {
        return LedgerAccountAddressFactory
                .customerAvailable(
                        senderId,
                        LedgerCurrency.EUR
                ).value();
    }

    private String destination() {
        return LedgerAccountAddressFactory
                .customerAvailable(
                        recipientId,
                        LedgerCurrency.EUR
                ).value();
    }

    private FormanceTransactionData transaction(
            String destinationAddress,
            long amount,
            String asset,
            Map<String, String> transactionMetadata,
            Boolean reverted
    ) {

        Instant now = Instant.parse(
                "2026-10-30T20:00:00Z"
        );

        return new FormanceTransactionData(
                List.of(
                        new FormanceTransactionPosting(
                                source(),
                                destinationAddress,
                                amount,
                                asset
                        )
                ),
                transactionMetadata,
                now,
                REFERENCE,
                42L,
                now,
                now,
                reverted
        );
    }

    private FormanceTransactionData validTransaction() {
        return transaction(
                destination(),
                5_000L,
                "EUR/2",
                metadata(),
                false
        );
    }

    private FormanceTransactionsResponse page(
            boolean hasMore,
            String next,
            FormanceTransactionData... transactions
    ) {

        return new FormanceTransactionsResponse(
                new FormanceTransactionsCursor(
                        50L,
                        hasMore,
                        next,
                        List.of(transactions)
                )
        );
    }

    private void firstPage(
            FormanceTransactionData... transactions
    ) {

        when(client.getTransactionsByReference(
                REFERENCE,
                50,
                null
        )).thenReturn(
                page(false, null, transactions)
        );
    }

    @Test
    void exactPostingIsVerified() {

        firstPage(validTransaction());

        var result = verifier.inspect(expected);

        assertEquals(
                FormanceInternalTransferEvidenceVerifier
                        .Finding.MATCHED,
                result.finding()
        );

        assertEquals(
                42L,
                result.transactionId()
        );
    }

    @Test
    void incorrectAmountIsInconclusive() {

        firstPage(transaction(
                destination(),
                6_000L,
                "EUR/2",
                metadata(),
                false
        ));

        assertEquals(
                FormanceInternalTransferEvidenceVerifier
                        .Finding.INCONCLUSIVE,
                verifier.inspect(expected).finding()
        );
    }

    @Test
    void incorrectDestinationIsInconclusive() {

        firstPage(transaction(
                "users:wrong:wallet:EUR:available",
                5_000L,
                "EUR/2",
                metadata(),
                false
        ));

        assertEquals(
                FormanceInternalTransferEvidenceVerifier
                        .Finding.INCONCLUSIVE,
                verifier.inspect(expected).finding()
        );
    }

    @Test
    void incorrectAssetIsInconclusive() {

        firstPage(transaction(
                destination(),
                5_000L,
                "USD/2",
                metadata(),
                false
        ));

        assertEquals(
                FormanceInternalTransferEvidenceVerifier
                        .Finding.INCONCLUSIVE,
                verifier.inspect(expected).finding()
        );
    }

    @Test
    void incorrectMetadataIsInconclusive() {

        firstPage(transaction(
                destination(),
                5_000L,
                "EUR/2",
                Map.of(
                        "neos.operation", "payout_reserve",
                        "neos.operation_id", OPERATION_ID,
                        "neos.currency", "EUR"
                ),
                false
        ));

        assertEquals(
                FormanceInternalTransferEvidenceVerifier
                        .Finding.INCONCLUSIVE,
                verifier.inspect(expected).finding()
        );
    }

    @Test
    void revertedTransactionIsNotAccepted() {

        firstPage(transaction(
                destination(),
                5_000L,
                "EUR/2",
                metadata(),
                true
        ));

        assertEquals(
                FormanceInternalTransferEvidenceVerifier
                        .Finding.INCONCLUSIVE,
                verifier.inspect(expected).finding()
        );
    }

    @Test
    void missingReversalFlagIsNotAccepted() {

        firstPage(transaction(
                destination(),
                5_000L,
                "EUR/2",
                metadata(),
                null
        ));

        assertEquals(
                FormanceInternalTransferEvidenceVerifier
                        .Finding.INCONCLUSIVE,
                verifier.inspect(expected).finding()
        );
    }

    @Test
    void noTransactionDoesNotAuthorizeRetry() {

        firstPage();

        var result = verifier.inspect(expected);

        assertEquals(
                FormanceInternalTransferEvidenceVerifier
                        .Finding.NOT_FOUND_UNCONFIRMED,
                result.finding()
        );

        assertNull(result.transactionId());
    }

    @Test
    void allPagesAreCheckedBeforeConfirmation() {

        when(client.getTransactionsByReference(
                REFERENCE, 50, null
        )).thenReturn(
                page(true, "opaque-next", validTransaction())
        );

        when(client.getTransactionsByReference(
                REFERENCE, 50, "opaque-next"
        )).thenReturn(
                page(false, null)
        );

        var result = verifier.inspect(expected);

        assertEquals(
                FormanceInternalTransferEvidenceVerifier
                        .Finding.MATCHED,
                result.finding()
        );

        verify(client).getTransactionsByReference(
                REFERENCE, 50, null
        );

        verify(client).getTransactionsByReference(
                REFERENCE, 50, "opaque-next"
        );
    }

    @Test
    void duplicateReferenceAcrossPagesIsInconclusive() {

        when(client.getTransactionsByReference(
                REFERENCE, 50, null
        )).thenReturn(
                page(true, "opaque-next", validTransaction())
        );

        when(client.getTransactionsByReference(
                REFERENCE, 50, "opaque-next"
        )).thenReturn(
                page(false, null, validTransaction())
        );

        assertEquals(
                FormanceInternalTransferEvidenceVerifier
                        .Finding.INCONCLUSIVE,
                verifier.inspect(expected).finding()
        );
    }
}
