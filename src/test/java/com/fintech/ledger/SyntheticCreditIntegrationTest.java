package com.fintech.ledger;

import com.fintech.notifications.TransactionNotificationDirection;

import com.fintech.notifications.TransactionNotificationType;

import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.adapter.formance.FormanceLedgerProvider;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddress;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.FinancialTransactionNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigInteger;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(
        classes = {
                LedgerConfiguration.class,
                FormanceClient.class,
                FormanceLedgerProvider.class,
                WalletProvisioningService.class
        }
)
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_IT",
        matches = "true"
)
class SyntheticCreditIntegrationTest {

    @Autowired
    private LedgerProvider ledgerProvider;

    @Autowired
    private WalletProvisioningService walletProvisioningService;

    @Autowired
    private FormanceClient formanceClient;

    private FinancialTransactionNotificationService
            transactionNotificationService;

    private SyntheticCreditService syntheticCreditService;

    @BeforeEach
    void setUp() {

        /*
         * This test is specifically focused on:
         *
         * SyntheticCreditService
         *        ->
         * LedgerProvider
         *        ->
         * FormanceLedgerProvider
         *        ->
         * REAL Formance ledger
         *
         * Notification persistence/delivery is tested independently
         * in the notification integration tests.
         *
         * Therefore the notification service is mocked here.
         */
        transactionNotificationService =
                mock(
                        FinancialTransactionNotificationService.class
                );

        /*
         * IMPORTANT:
         *
         * Assign the FIELD.
         *
         * Do not write:
         *
         * SyntheticCreditService syntheticCreditService = ...
         *
         * because that would create a local variable and leave
         * this.syntheticCreditService null.
         */
        syntheticCreditService =
                new SyntheticCreditService(
                        ledgerProvider,
                        transactionNotificationService
                );
    }

    @DynamicPropertySource
    static void properties(
            DynamicPropertyRegistry registry
    ) {

        registry.add(
                "neos.ledger.formance.base-url",
                () ->
                        System.getenv()
                                .getOrDefault(
                                        "FORMANCE_BASE_URL",
                                        "http://localhost:8080"
                                )
        );

        registry.add(
                "neos.ledger.formance.ledger-name",
                () ->
                        System.getenv()
                                .getOrDefault(
                                        "FORMANCE_LEDGER_NAME",
                                        "neos"
                                )
        );

        registry.add(
                "neos.ledger.formance.schema-version",
                () ->
                        System.getenv()
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
    void creditsExistingWalletThroughRealIncomingPaymentFlowAndIsIdempotent() {

        UUID userId =
                UUID.randomUUID();

        LedgerCurrency currency =
                LedgerCurrency.EUR;

        String operationId =
                "c3_credit_"
                        + UUID.randomUUID()
                        .toString()
                        .replace(
                                "-",
                                ""
                        );

        long amountMinor =
                10_000L;

        /*
         * Simulate the state produced by C2:
         *
         * the approved customer already owns
         * zero-balance Formance wallets.
         */
        walletProvisioningService
                .provisionCustomerWallets(
                        userId
                );

        LedgerAccountAddress available =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                currency
                        );

        LedgerAccountAddress bank =
                LedgerAccountAddressFactory
                        .bankMaster(
                                "sponsor",
                                currency
                        );

        LedgerAccountAddress control =
                LedgerAccountAddressFactory
                        .safeguardedControl(
                                currency
                        );

        BigInteger availableBefore =
                balance(
                        available
                );

        BigInteger bankBefore =
                balance(
                        bank
                );

        BigInteger controlBefore =
                balance(
                        control
                );

        /*
         * --------------------------------------------------------
         * FIRST SYNTHETIC CREDIT
         * --------------------------------------------------------
         *
         * This must use the production incoming-payment accounting:
         *
         * world
         *   ->
         * bank:sponsor:master:EUR
         *
         * control:safeguarded:EUR
         *   ->
         * users:{userId}:wallet:EUR:available
         */
        LedgerOperationResult first =
                syntheticCreditService.credit(
                        userId,
                        currency,
                        amountMinor,
                        operationId
                );

        assertThat(
                first.transactionId()
        ).isPositive();

        assertThat(
                first.reference()
        ).isEqualTo(
                "incoming_payment:"
                        + operationId
        );

        BigInteger availableAfter =
                balance(
                        available
                );

        BigInteger bankAfter =
                balance(
                        bank
                );

        BigInteger controlAfter =
                balance(
                        control
                );

        /*
         * Customer available:
         *
         * +100 EUR
         */
        assertThat(
                availableAfter
                        .subtract(
                                availableBefore
                        )
        ).isEqualTo(
                BigInteger.valueOf(
                        amountMinor
                )
        );

        /*
         * Sponsor bank master:
         *
         * +100 EUR
         */
        assertThat(
                bankAfter
                        .subtract(
                                bankBefore
                        )
        ).isEqualTo(
                BigInteger.valueOf(
                        amountMinor
                )
        );

        /*
         * Safeguarded control:
         *
         * -100 EUR
         */
        assertThat(
                controlAfter
                        .subtract(
                                controlBefore
                        )
        ).isEqualTo(
                BigInteger.valueOf(
                        -amountMinor
                )
        );

        /*
         * Safeguarding allocation:
         *
         * bank increase == customer increase
         */
        assertThat(
                bankAfter
                        .subtract(
                                bankBefore
                        )
        ).isEqualTo(
                availableAfter
                        .subtract(
                                availableBefore
                        )
        );

        /*
         * Control is the negative counterpart
         * of the customer allocation.
         */
        assertThat(
                controlAfter
                        .subtract(
                                controlBefore
                        )
        ).isEqualTo(
                availableAfter
                        .subtract(
                                availableBefore
                        )
                        .negate()
        );

        /*
         * The successful financial operation should also trigger
         * the notification orchestration layer.
         *
         * We mock that layer here because this test's concern is
         * the real Formance accounting flow.
         */
        verify(
                transactionNotificationService,
                times(1)
        ).enqueueSafely(
                        userId,
                        first.transactionId(),
                        first.reference(),
                        TransactionNotificationType.INCOMING_PAYMENT,
                        TransactionNotificationDirection.CREDIT,
                        currency.currencyCode(),
                        currency.formanceAsset(),
                        amountMinor
                );

        /*
         * --------------------------------------------------------
         * IDEMPOTENT RETRY
         * --------------------------------------------------------
         *
         * Formance must return the same transaction for the
         * same operation ID.
         */
        LedgerOperationResult retry =
                syntheticCreditService.credit(
                        userId,
                        currency,
                        amountMinor,
                        operationId
                );

        assertThat(
                retry.transactionId()
        ).isEqualTo(
                first.transactionId()
        );

        assertThat(
                retry.reference()
        ).isEqualTo(
                first.reference()
        );

        /*
         * Retry must NOT create another financial credit.
         */
        assertThat(
                balance(
                        available
                )
        ).isEqualTo(
                availableAfter
        );

        assertThat(
                balance(
                        bank
                )
        ).isEqualTo(
                bankAfter
        );

        assertThat(
                balance(
                        control
                )
        ).isEqualTo(
                controlAfter
        );

        /*
         * SyntheticCreditService invokes enqueueSafely again
         * because the application request was retried.
         *
         * This is expected.
         *
         * NotificationOutboxService performs the actual
         * notification deduplication using:
         *
         * transactionId + userId + channel
         *
         * Since Formance returned the same transaction ID,
         * the real outbox would still contain one notification.
         */
        verify(
                transactionNotificationService,
                times(2)
        ).enqueueSafely(
                        userId,
                        first.transactionId(),
                        first.reference(),
                        TransactionNotificationType.INCOMING_PAYMENT,
                        TransactionNotificationDirection.CREDIT,
                        currency.currencyCode(),
                        currency.formanceAsset(),
                        amountMinor
                );
    }

    private BigInteger balance(
            LedgerAccountAddress account
    ) {

        return formanceClient
                .getAccountBalance(
                        account.value(),
                        account.formanceAsset()
                );
    }
}