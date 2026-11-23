package com.fintech.ledger;

import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.adapter.formance.FormanceLedgerProvider;
import com.fintech.ledger.adapter.formance.FormanceTransactionData;
import com.fintech.ledger.adapter.formance.FormanceTransactionsResponse;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddress;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(classes = {
        LedgerConfiguration.class,
        FormanceClient.class,
        FormanceLedgerProvider.class
})
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_IT",
        matches = "true"
)
class TransactionHistoryFormanceIntegrationTest {

    @Autowired
    private LedgerProvider ledgerProvider;

    @Autowired
    private FormanceClient formanceClient;

    @DynamicPropertySource
    static void formanceProperties(
            DynamicPropertyRegistry registry
    ) {

        registry.add(
                "neos.ledger.formance.base-url",
                () -> System.getenv()
                        .getOrDefault(
                                "FORMANCE_BASE_URL",
                                "http://localhost:8080"
                        )
        );

        registry.add(
                "neos.ledger.formance.ledger-name",
                () -> System.getenv()
                        .getOrDefault(
                                "FORMANCE_LEDGER_NAME",
                                "neos"
                        )
        );

        registry.add(
                "neos.ledger.formance.schema-version",
                () -> System.getenv()
                        .getOrDefault(
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
                () -> "5s"
        );
    }

    @Test
    void readsCustomerTransactionHistoryWithCursorPagination() {

        UUID userId =
                UUID.randomUUID();

        String suffix =
                UUID.randomUUID()
                        .toString()
                        .replace(
                                "-",
                                ""
                        );

        String operationA =
                "c4_history_a_"
                        + suffix;

        String operationB =
                "c4_history_b_"
                        + suffix;

        /*
         * Create two REAL Formance transactions.
         *
         * Each transaction performs:
         *
         * world -> bank
         * control -> customer available
         */
        ledgerProvider.recordIncomingPayment(
                userId,
                "c4_history_bank",
                LedgerCurrency.EUR,
                1_000,
                operationA
        );

        ledgerProvider.recordIncomingPayment(
                userId,
                "c4_history_bank",
                LedgerCurrency.EUR,
                2_000,
                operationB
        );

        LedgerAccountAddress customerAccount =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                LedgerCurrency.EUR
                        );

        /*
         * pageSize = 1 deliberately forces pagination.
         */
        FormanceTransactionsResponse firstPage =
                formanceClient
                        .getTransactionsForAccount(
                                customerAccount.value(),
                                1,
                                null
                        );

        assertThat(
                firstPage.cursor()
        ).isNotNull();

        assertThat(
                firstPage.cursor().pageSize()
        ).isEqualTo(
                1
        );

        assertThat(
                firstPage.cursor().data()
        ).hasSize(
                1
        );

        assertThat(
                firstPage.cursor().hasMore()
        ).isTrue();

        assertThat(
                firstPage.cursor().next()
        ).isNotBlank();

        FormanceTransactionData first =
                firstPage
                        .cursor()
                        .data()
                        .getFirst();

        assertThat(
                first.id()
        ).isPositive();

        assertThat(
                first.reverted()
        ).isFalse();

        assertThat(
                first.timestamp()
        ).isNotNull();

        assertThat(
                first.insertedAt()
        ).isNotNull();

        assertThat(
                first.reference()
        ).isIn(
                "incoming_payment:" + operationA,
                "incoming_payment:" + operationB
        );

        assertThat(
                first.metadata()
        )
                .containsEntry(
                        "neos.operation",
                        "incoming_payment"
                )
                .containsEntry(
                        "neos.user_id",
                        userId.toString()
                )
                .containsEntry(
                        "neos.currency",
                        "EUR"
                );

        assertThat(
                first.postings()
        ).hasSize(
                2
        );

        /*
         * Our incoming-payment transaction must contain
         * one posting whose destination is this user's wallet.
         */
        assertThat(
                first.postings()
        ).anySatisfy(
                posting -> {

                    assertThat(
                            posting.destination()
                    ).isEqualTo(
                            customerAccount.value()
                    );

                    assertThat(
                            posting.asset()
                    ).isEqualTo(
                            "EUR/2"
                    );

                    assertThat(
                            posting.amount()
                    ).isIn(
                            1_000L,
                            2_000L
                    );
                }
        );

        /*
         * Formance says the next request must send only
         * the opaque cursor.
         */
        FormanceTransactionsResponse secondPage =
                formanceClient
                        .getTransactionsForAccount(
                                customerAccount.value(),
                                1,
                                firstPage.cursor().next()
                        );

        assertThat(
                secondPage.cursor().data()
        ).hasSize(
                1
        );

        FormanceTransactionData second =
                secondPage
                        .cursor()
                        .data()
                        .getFirst();

        assertThat(
                second.id()
        ).isPositive();

        assertThat(
                second.id()
        ).isNotEqualTo(
                first.id()
        );

        assertThat(
                second.reference()
        ).isIn(
                "incoming_payment:" + operationA,
                "incoming_payment:" + operationB
        );

        assertThat(
                second.reference()
        ).isNotEqualTo(
                first.reference()
        );

        assertThat(
                second.metadata()
        )
                .containsEntry(
                        "neos.user_id",
                        userId.toString()
                )
                .containsEntry(
                        "neos.operation",
                        "incoming_payment"
                );

        /*
         * Between page one and page two we must have
         * discovered BOTH operations exactly once.
         */
        assertThat(
                java.util.List.of(
                        first.reference(),
                        second.reference()
                )
        ).containsExactlyInAnyOrder(
                "incoming_payment:" + operationA,
                "incoming_payment:" + operationB
        );
    }
}