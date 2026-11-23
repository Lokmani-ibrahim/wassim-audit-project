
package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.LedgerConfiguration;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(classes = {
        LedgerConfiguration.class,
        FormanceClient.class,
        FormanceInternalTransferEvidenceVerifier.class
})
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_IT",
        matches = "true"
)
class FormanceReferenceLookupLiveIntegrationTest {

    @Autowired
    private FormanceClient client;

    @Autowired
    private FormanceInternalTransferEvidenceVerifier verifier;

    @DynamicPropertySource
    static void formanceProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "neos.ledger.formance.base-url",
                () -> System.getenv().getOrDefault(
                        "FORMANCE_BASE_URL",
                        "http://localhost:8080"
                )
        );

        registry.add(
                "neos.ledger.formance.ledger-name",
                () -> System.getenv().getOrDefault(
                        "FORMANCE_LEDGER_NAME",
                        "neos"
                )
        );

        registry.add(
                "neos.ledger.formance.schema-version",
                () -> System.getenv().getOrDefault(
                        "FORMANCE_SCHEMA_VERSION",
                        "v1.1.0"
                )
        );

        registry.add(
                "neos.ledger.formance.connect-timeout",
                () -> "2s"
        );

        registry.add(
                "neos.ledger.formance.read-timeout",
                () -> "10s"
        );
    }

    private String operationId() {
        return System.getenv().getOrDefault(
                "FORMANCE_LOOKUP_OPERATION_ID",
                "c1_transfer_002"
        );
    }

    private UUID senderId() {
        return UUID.fromString(
                System.getenv().getOrDefault(
                        "FORMANCE_LOOKUP_SENDER_ID",
                        "00000000-0000-0000-0000-00000000c201"
                )
        );
    }

    private UUID recipientId() {
        return UUID.fromString(
                System.getenv().getOrDefault(
                        "FORMANCE_LOOKUP_RECIPIENT_ID",
                        "00000000-0000-0000-0000-00000000c202"
                )
        );
    }

    private long amountMinor() {
        return Long.parseLong(
                System.getenv().getOrDefault(
                        "FORMANCE_LOOKUP_AMOUNT_MINOR",
                        "500"
                )
        );
    }

    @Test
    void connectsToExistingLedger() {
        assertDoesNotThrow(client::verifyConnection);
    }

    @Test
    void referenceLookupReturnsHistoricalTransfer() {

        String reference =
                "internal_transfer:" + operationId();

        FormanceTransactionsResponse response =
                client.getTransactionsByReference(
                        reference,
                        25,
                        null
                );

        assertNotNull(response);
        assertNotNull(response.cursor());
        assertNotNull(response.cursor().data());

        assertFalse(
                response.cursor().data().isEmpty(),
                "Historical transfer not found. "
                        + "Confirm that this operation exists "
                        + "in the selected Formance ledger."
        );

        // Every returned transaction must satisfy
        // the exact reference filter.
        assertTrue(
                response.cursor().data().stream()
                        .allMatch(transaction ->
                                transaction != null
                                        && reference.equals(
                                        transaction.reference()
                                )
                        ),
                "Formance returned an unexpected reference"
        );
    }

    @Test
    void historicalTransferPassesEvidenceVerification() {

        var expected =
                new FormanceInternalTransferEvidenceVerifier
                        .ExpectedTransfer(
                        senderId(),
                        recipientId(),
                        LedgerCurrency.EUR,
                        amountMinor(),
                        operationId()
                );

        var result = verifier.inspect(expected);

        assertEquals(
                FormanceInternalTransferEvidenceVerifier
                        .Finding.MATCHED,
                result.finding(),
                () -> "Real Formance evidence did not match: "
                        + result.explanation()
        );

        assertNotNull(result.transactionId());

        System.out.println(
                "Read-only Formance verification succeeded. "
                        + "Transaction ID: "
                        + result.transactionId()
        );
    }
}
