
package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false",
        "neos.backoffice.synthetic-credit.enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.payouts.ledger-finalization-enabled=false",
        "neos.payouts.reconciliation-enabled=false",
        "neos.payouts.notification-repair-enabled=false"
})
@EnabledIfEnvironmentVariable(
        named = "RUN_PAYOUT_EVIDENCE_IT",
        matches = "true"
)
class FormancePayoutEvidenceReadOnlyIntegrationTest {

    @Autowired
    private FormancePayoutEvidenceVerifier verifier;
    @Autowired
    private FormanceClient formanceClient;

    private static String requiredEnvironment(String key) {

        String value = System.getenv(key);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required environment variable: "
                            + key
            );
        }

        return value.trim();
    }

    @Test
    void existingFormancePayoutMatchesExpectedEvidence() {

        var action =
                FormancePayoutEvidenceVerifier.Action.valueOf(
                        requiredEnvironment(
                                "NEOS_EVIDENCE_ACTION"
                        ).toUpperCase(Locale.ROOT)
                );

        UUID userId = UUID.fromString(
                requiredEnvironment(
                        "NEOS_EVIDENCE_USER_ID"
                )
        );

        LedgerCurrency currency =
                LedgerCurrency.valueOf(
                        requiredEnvironment(
                                "NEOS_EVIDENCE_CURRENCY"
                        ).toUpperCase(Locale.ROOT)
                );

        long amountMinor = Long.parseLong(
                requiredEnvironment(
                        "NEOS_EVIDENCE_AMOUNT_MINOR"
                )
        );

        String operationId = requiredEnvironment(
                "NEOS_EVIDENCE_OPERATION_ID"
        );

        String bankId = System.getenv(
                "NEOS_EVIDENCE_BANK_ID"
        );

        var expected =
                new FormancePayoutEvidenceVerifier.ExpectedPayout(
                        action,
                        userId,
                        bankId,
                        currency,
                        amountMinor,
                        operationId
                );

// GET only: no Formance createScriptTransaction call.
        var response = formanceClient.getTransactionsByReference(
                expected.reference(),
                50,
                null
        );

        assertNotNull(response);
        assertNotNull(response.cursor());
        assertNotNull(response.cursor().data());

        var transactions = response.cursor().data();

        assertEquals(
                1,
                transactions.size(),
                "Expected one transaction on the first lookup page"
        );

        var transaction = transactions.get(0);

        assertEquals(
                expected.reference(),
                transaction.reference()
        );

        assertEquals(
                Boolean.FALSE,
                transaction.reverted()
        );

        assertNotNull(transaction.metadata());

        assertEquals(
                action.operationType(),
                transaction.metadata().get("neos.operation")
        );

        assertEquals(
                operationId,
                transaction.metadata().get("neos.operation_id")
        );

        assertEquals(
                currency.currencyCode(),
                transaction.metadata().get("neos.currency")
        );

        assertNotNull(transaction.postings());

        assertEquals(
                action == FormancePayoutEvidenceVerifier.Action.SETTLE
                        ? 2
                        : 1,
                transaction.postings().size()
        );

        if (action == FormancePayoutEvidenceVerifier.Action.SETTLE) {

            assertEquals(
                    userId.toString(),
                    transaction.metadata().get("neos.user_id")
            );

            assertEquals(
                    bankId,
                    transaction.metadata().get("neos.bank_id")
            );

            assertTrue(
                    transaction.postings().stream()
                            .anyMatch(posting ->
                                    "world".equals(
                                            posting.destination()
                                    )
                            ),
                    "Formance world account representation differs"
            );
        }

// The existing verifier provides the full,
// order-independent accounting verification.
        var verified = verifier.inspect(expected);

        assertEquals(
                FormancePayoutEvidenceVerifier.Finding.MATCHED,
                verified.finding(),
                verified.explanation()
        );

        assertEquals(
                transaction.id(),
                verified.transactionId()
        );

    }
}
