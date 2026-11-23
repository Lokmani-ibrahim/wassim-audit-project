package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.LedgerConfiguration;
import com.fintech.ledger.LedgerTransactionRejectedException;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddress;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(classes = {
        LedgerConfiguration.class,
        FormanceClient.class,
        FormanceLedgerProvider.class
})
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_IT",
        matches = "true"
)
class FormanceConnectivityIntegrationTest {

    /*
     * ----------------------------------------------------------------
     * Generic C1 connectivity/schema test user
     * ----------------------------------------------------------------
     */

    private static final UUID VALIDATION_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c101"
            );
    private static final UUID INCOMING_PAYMENT_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c401"
            );
    private static final UUID ATTRIBUTED_FROM_SUSPENSE_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c402"
            );
    private static final UUID PAYOUT_SETTLEMENT_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c501"
            );

    private static final UUID INSUFFICIENT_HOLD_SETTLEMENT_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c502"
            );

    private static final UUID INSUFFICIENT_BANK_SETTLEMENT_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c503"
            );
    /*
     * ----------------------------------------------------------------
     * Internal transfer scenario
     * ----------------------------------------------------------------
     */

    private static final UUID TRANSFER_SOURCE_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c201"
            );

    private static final UUID TRANSFER_DESTINATION_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c202"
            );

    /*
     * ----------------------------------------------------------------
     * Payout reservation scenario
     * ----------------------------------------------------------------
     */

    private static final UUID RESERVE_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c203"
            );

    /*
     * ----------------------------------------------------------------
     * Payout release scenario
     * ----------------------------------------------------------------
     */

    private static final UUID RELEASE_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c204"
            );

    /*
     * ----------------------------------------------------------------
     * Fee scenario
     * ----------------------------------------------------------------
     */

    private static final UUID FEE_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c205"
            );

    /*
     * ----------------------------------------------------------------
     * Insufficient-funds scenarios.
     *
     * These users must NEVER be seeded.
     * ----------------------------------------------------------------
     */

    private static final UUID INSUFFICIENT_TRANSFER_SOURCE =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c301"
            );

    private static final UUID INSUFFICIENT_TRANSFER_DESTINATION =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c302"
            );

    private static final UUID INSUFFICIENT_RESERVE_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c303"
            );

    private static final UUID INSUFFICIENT_RELEASE_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c304"
            );

    private static final UUID INSUFFICIENT_FEE_USER =
            UUID.fromString(
                    "00000000-0000-0000-0000-00000000c305"
            );
    private BigInteger balance(
            LedgerAccountAddress account
    ) {

        return formanceClient
                .getAccountBalance(
                        account.value(),
                        account.formanceAsset()
                );
    }
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

    /*
     * ================================================================
     * 1. CONNECTIVITY
     * ================================================================
     */

    @Test
    void connectsToRealNeosLedger() {

        assertThatCode(
                ledgerProvider::verifyConnection
        ).doesNotThrowAnyException();
    }

    /*
     * ================================================================
     * 2. LOW-LEVEL IDEMPOTENCY
     * ================================================================
     */

    @Test
    void createsIdempotentValidationCredit() {

        LedgerAccountAddress destination =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                VALIDATION_USER,
                                LedgerCurrency.EUR
                        );

        FormanceCreateTransactionRequest request =
                new FormanceCreateTransactionRequest(
                        List.of(
                                new FormancePosting(
                                        "world",
                                        destination.value(),
                                        destination.formanceAsset(),
                                        100
                                )
                        ),
                        Map.of(
                                "neos.operation",
                                "c1_validation_credit",
                                "neos.environment",
                                "development"
                        ),
                        "c1-validation-world-credit-v1"
                );

        FormanceTransactionResult first =
                formanceClient.createTransaction(
                        request,
                        "c1-validation-world-credit-v1"
                );

        FormanceTransactionResult retry =
                formanceClient.createTransaction(
                        request,
                        "c1-validation-world-credit-v1"
                );

        assertThat(
                first.transactionId()
        ).isPositive();

        assertThat(
                retry.transactionId()
        ).isEqualTo(
                first.transactionId()
        );

        assertThat(
                retry.reference()
        ).isEqualTo(
                "c1-validation-world-credit-v1"
        );
    }

    /*
     * ================================================================
     * 3. SCHEMA ACCEPTANCE
     * ================================================================
     */

    @Test
    void acceptsValidNeosAccountAgainstSchema() {

        LedgerAccountAddress destination =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                UUID.fromString(
                                        "550e8400-e29b-41d4-a716-446655440000"
                                ),
                                LedgerCurrency.EUR
                        );

        FormanceCreateTransactionRequest request =
                new FormanceCreateTransactionRequest(
                        List.of(
                                new FormancePosting(
                                        "world",
                                        destination.value(),
                                        destination.formanceAsset(),
                                        100
                                )
                        ),
                        Map.of(
                                "neos.operation",
                                "schema_validation"
                        ),
                        "c1-schema-valid-dry-run"
                );

        assertThatCode(
                () -> formanceClient
                        .validateTransaction(
                                request
                        )
        ).doesNotThrowAnyException();
    }

    /*
     * ================================================================
     * 4. SCHEMA REJECTION
     * ================================================================
     */

    @Test
    void rejectsAccountOutsideNeosSchema() {

        FormanceCreateTransactionRequest request =
                new FormanceCreateTransactionRequest(
                        List.of(
                                new FormancePosting(
                                        "world",
                                        "random:garbage:account",
                                        "EUR/2",
                                        100
                                )
                        ),
                        Map.of(
                                "neos.operation",
                                "schema_rejection_test"
                        ),
                        "c1-schema-invalid-dry-run"
                );

        assertThatThrownBy(
                () -> formanceClient
                        .validateTransaction(
                                request
                        )
        )
                .isInstanceOf(
                        LedgerTransactionRejectedException.class
                );
    }

    /*
     * ================================================================
     * 5. INTERNAL TRANSFER
     * ================================================================
     */

    @Test
    void transfersBetweenUsersAndIsIdempotent() {

        seedAvailableBalance(
                TRANSFER_SOURCE_USER,
                LedgerCurrency.EUR,
                1_000,
                "c1-transfer-seed-v2"
        );

        LedgerOperationResult first =
                ledgerProvider.transferBetweenUsers(
                        TRANSFER_SOURCE_USER,
                        TRANSFER_DESTINATION_USER,
                        LedgerCurrency.EUR,
                        500,
                        "c1_transfer_002"
                );

        LedgerOperationResult retry =
                ledgerProvider.transferBetweenUsers(
                        TRANSFER_SOURCE_USER,
                        TRANSFER_DESTINATION_USER,
                        LedgerCurrency.EUR,
                        500,
                        "c1_transfer_002"
                );

        assertThat(
                first.transactionId()
        ).isPositive();

        assertThat(
                retry.transactionId()
        ).isEqualTo(
                first.transactionId()
        );

        assertThat(
                first.reference()
        ).isEqualTo(
                "internal_transfer:c1_transfer_002"
        );

        assertThat(
                retry.reference()
        ).isEqualTo(
                first.reference()
        );
    }

    /*
     * ================================================================
     * 6. PAYOUT RESERVE
     * ================================================================
     */

    @Test
    void reservesPayoutAndIsIdempotent() {

        seedAvailableBalance(
                RESERVE_USER,
                LedgerCurrency.EUR,
                1_000,
                "c1-reserve-seed-v2"
        );

        LedgerOperationResult first =
                ledgerProvider.reservePayout(
                        RESERVE_USER,
                        LedgerCurrency.EUR,
                        400,
                        "c1_payout_reserve_002"
                );

        LedgerOperationResult retry =
                ledgerProvider.reservePayout(
                        RESERVE_USER,
                        LedgerCurrency.EUR,
                        400,
                        "c1_payout_reserve_002"
                );

        assertThat(
                first.transactionId()
        ).isPositive();

        assertThat(
                retry.transactionId()
        ).isEqualTo(
                first.transactionId()
        );

        assertThat(
                first.reference()
        ).isEqualTo(
                "payout_reserve:c1_payout_reserve_002"
        );

        assertThat(
                retry.reference()
        ).isEqualTo(
                first.reference()
        );
    }

    /*
     * ================================================================
     * 7. PAYOUT RELEASE
     * ================================================================
     */

    @Test
    void releasesPayoutAndIsIdempotent() {

        seedAvailableBalance(
                RELEASE_USER,
                LedgerCurrency.EUR,
                1_000,
                "c1-release-seed-v2"
        );

        LedgerOperationResult reserve =
                ledgerProvider.reservePayout(
                        RELEASE_USER,
                        LedgerCurrency.EUR,
                        400,
                        "c1_payout_release_002"
                );

        assertThat(
                reserve.transactionId()
        ).isPositive();

        LedgerOperationResult firstRelease =
                ledgerProvider.releasePayout(
                        RELEASE_USER,
                        LedgerCurrency.EUR,
                        400,
                        "c1_payout_release_002"
                );

        LedgerOperationResult retryRelease =
                ledgerProvider.releasePayout(
                        RELEASE_USER,
                        LedgerCurrency.EUR,
                        400,
                        "c1_payout_release_002"
                );

        assertThat(
                firstRelease.transactionId()
        ).isPositive();

        assertThat(
                retryRelease.transactionId()
        ).isEqualTo(
                firstRelease.transactionId()
        );

        assertThat(
                firstRelease.reference()
        ).isEqualTo(
                "payout_release:c1_payout_release_002"
        );

        assertThat(
                retryRelease.reference()
        ).isEqualTo(
                firstRelease.reference()
        );
    }

    /*
     * ================================================================
     * 8. FEE COLLECTION
     * ================================================================
     */

    @Test
    void collectsFeeAndIsIdempotent() {

        seedAvailableBalance(
                FEE_USER,
                LedgerCurrency.TND,
                5_000,
                "c1-fee-seed-v2"
        );

        LedgerOperationResult first =
                ledgerProvider.collectFee(
                        FEE_USER,
                        LedgerCurrency.TND,
                        1_250,
                        "c1_fee_002"
                );

        LedgerOperationResult retry =
                ledgerProvider.collectFee(
                        FEE_USER,
                        LedgerCurrency.TND,
                        1_250,
                        "c1_fee_002"
                );

        assertThat(
                first.transactionId()
        ).isPositive();

        assertThat(
                retry.transactionId()
        ).isEqualTo(
                first.transactionId()
        );

        assertThat(
                first.reference()
        ).isEqualTo(
                "fee_collection:c1_fee_002"
        );

        assertThat(
                retry.reference()
        ).isEqualTo(
                first.reference()
        );
    }

    /*
     * ================================================================
     * 9. INTERNAL TRANSFER - INSUFFICIENT FUNDS
     * ================================================================
     */

    @Test
    void rejectsTransferWhenSourceHasInsufficientFunds() {

        assertThatThrownBy(
                () -> ledgerProvider
                        .transferBetweenUsers(
                                INSUFFICIENT_TRANSFER_SOURCE,
                                INSUFFICIENT_TRANSFER_DESTINATION,
                                LedgerCurrency.EUR,
                                1_000,
                                "c1_insufficient_transfer_002"
                        )
        )
                .isInstanceOf(
                        LedgerTransactionRejectedException.class
                );
    }

    /*
     * ================================================================
     * 10. PAYOUT RESERVE - INSUFFICIENT FUNDS
     * ================================================================
     */

    @Test
    void rejectsPayoutReserveWhenAvailableBalanceIsInsufficient() {

        assertThatThrownBy(
                () -> ledgerProvider
                        .reservePayout(
                                INSUFFICIENT_RESERVE_USER,
                                LedgerCurrency.EUR,
                                1_000,
                                "c1_insufficient_reserve_002"
                        )
        )
                .isInstanceOf(
                        LedgerTransactionRejectedException.class
                );
    }

    /*
     * ================================================================
     * 11. PAYOUT RELEASE - INSUFFICIENT HOLD
     * ================================================================
     */

    @Test
    void rejectsPayoutReleaseWhenHoldBalanceIsInsufficient() {

        assertThatThrownBy(
                () -> ledgerProvider
                        .releasePayout(
                                INSUFFICIENT_RELEASE_USER,
                                LedgerCurrency.EUR,
                                1_000,
                                "c1_insufficient_release_002"
                        )
        )
                .isInstanceOf(
                        LedgerTransactionRejectedException.class
                );
    }

    /*
     * ================================================================
     * 12. FEE COLLECTION - INSUFFICIENT FUNDS
     * ================================================================
     */

    @Test
    void rejectsFeeCollectionWhenAvailableBalanceIsInsufficient() {

        assertThatThrownBy(
                () -> ledgerProvider
                        .collectFee(
                                INSUFFICIENT_FEE_USER,
                                LedgerCurrency.TND,
                                1_000,
                                "c1_insufficient_fee_002"
                        )
        )
                .isInstanceOf(
                        LedgerTransactionRejectedException.class
                );
    }

    /*
     * ================================================================
     * TEST-ONLY FUNDING HELPER
     * ================================================================
     *
     * "world" is used ONLY to arrange test balances.
     *
     * This method is deliberately not exposed through LedgerProvider,
     * because production Neos code must never be able to manufacture
     * customer money arbitrarily.
     */

    private void seedAvailableBalance(
            UUID userId,
            LedgerCurrency currency,
            long amountMinor,
            String reference
    ) {

        LedgerAccountAddress destination =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                currency
                        );

        FormanceCreateTransactionRequest request =
                new FormanceCreateTransactionRequest(
                        List.of(
                                new FormancePosting(
                                        "world",
                                        destination.value(),
                                        destination.formanceAsset(),
                                        amountMinor
                                )
                        ),
                        Map.of(
                                "neos.operation",
                                "integration_test_seed",
                                "neos.environment",
                                "development",
                                "neos.currency",
                                currency.currencyCode()
                        ),
                        reference
                );

        formanceClient.createTransaction(
                request,
                reference
        );
    }
    @Test
    void recordsAttributedIncomingPaymentAndIsIdempotent() {

        LedgerOperationResult first =
                ledgerProvider.recordIncomingPayment(
                        INCOMING_PAYMENT_USER,
                        "sponsor",
                        LedgerCurrency.EUR,
                        10_000,
                        "c1_incoming_001"
                );

        LedgerOperationResult retry =
                ledgerProvider.recordIncomingPayment(
                        INCOMING_PAYMENT_USER,
                        "sponsor",
                        LedgerCurrency.EUR,
                        10_000,
                        "c1_incoming_001"
                );

        assertThat(
                first.transactionId()
        ).isPositive();

        assertThat(
                retry.transactionId()
        ).isEqualTo(
                first.transactionId()
        );

        assertThat(
                first.reference()
        ).isEqualTo(
                "incoming_payment:c1_incoming_001"
        );

        assertThat(
                retry.reference()
        ).isEqualTo(
                first.reference()
        );
    }
    @Test
    void recordsUnattributedIncomingPaymentAndIsIdempotent() {

        LedgerOperationResult first =
                ledgerProvider.recordUnattributedIncomingPayment(
                        "sponsor",
                        LedgerCurrency.EUR,
                        10_000,
                        "c1_unknown_incoming_001"
                );

        LedgerOperationResult retry =
                ledgerProvider.recordUnattributedIncomingPayment(
                        "sponsor",
                        LedgerCurrency.EUR,
                        10_000,
                        "c1_unknown_incoming_001"
                );

        assertThat(
                first.transactionId()
        ).isPositive();

        assertThat(
                retry.transactionId()
        ).isEqualTo(
                first.transactionId()
        );

        assertThat(
                first.reference()
        ).isEqualTo(
                "incoming_unattributed:c1_unknown_incoming_001"
        );

        assertThat(
                retry.reference()
        ).isEqualTo(
                first.reference()
        );
    }
    @Test
    void attributesIncomingPaymentFromSuspenseAndIsIdempotent() {

        /*
         * Arrange:
         * create an actual unidentified incoming payment first.
         */
        LedgerOperationResult incoming =
                ledgerProvider.recordUnattributedIncomingPayment(
                        "sponsor",
                        LedgerCurrency.EUR,
                        7_500,
                        "c1_unknown_for_attribution_001"
                );

        assertThat(
                incoming.transactionId()
        ).isPositive();

        /*
         * Act:
         * beneficiary is now identified.
         */
        LedgerOperationResult first =
                ledgerProvider.attributeIncomingPayment(
                        ATTRIBUTED_FROM_SUSPENSE_USER,
                        LedgerCurrency.EUR,
                        7_500,
                        "c1_attribution_001"
                );

        LedgerOperationResult retry =
                ledgerProvider.attributeIncomingPayment(
                        ATTRIBUTED_FROM_SUSPENSE_USER,
                        LedgerCurrency.EUR,
                        7_500,
                        "c1_attribution_001"
                );

        assertThat(
                first.transactionId()
        ).isPositive();

        assertThat(
                retry.transactionId()
        ).isEqualTo(
                first.transactionId()
        );

        assertThat(
                first.reference()
        ).isEqualTo(
                "incoming_attribution:c1_attribution_001"
        );

        assertThat(
                retry.reference()
        ).isEqualTo(
                first.reference()
        );
    }
    @Test
    void settlesPayoutAndIsIdempotent() {

        /*
         * Real inbound payment:
         *
         * world -> bank             €100
         * control -> customer       €100
         */
        LedgerOperationResult incoming =
                ledgerProvider.recordIncomingPayment(
                        PAYOUT_SETTLEMENT_USER,
                        "settlement_bank",
                        LedgerCurrency.EUR,
                        10_000,
                        "c1_settlement_funding_001"
                );

        assertThat(
                incoming.transactionId()
        ).isPositive();

        /*
         * Reserve €30:
         *
         * available -> hold
         */
        LedgerOperationResult reserve =
                ledgerProvider.reservePayout(
                        PAYOUT_SETTLEMENT_USER,
                        LedgerCurrency.EUR,
                        3_000,
                        "c1_payout_settle_001"
                );

        assertThat(
                reserve.transactionId()
        ).isPositive();

        /*
         * Bank confirms external payout:
         *
         * bank -> world
         * hold -> control
         */
        LedgerOperationResult first =
                ledgerProvider.settlePayout(
                        PAYOUT_SETTLEMENT_USER,
                        "settlement_bank",
                        LedgerCurrency.EUR,
                        3_000,
                        "c1_payout_settle_001"
                );

        LedgerOperationResult retry =
                ledgerProvider.settlePayout(
                        PAYOUT_SETTLEMENT_USER,
                        "settlement_bank",
                        LedgerCurrency.EUR,
                        3_000,
                        "c1_payout_settle_001"
                );

        assertThat(
                first.transactionId()
        ).isPositive();

        assertThat(
                retry.transactionId()
        ).isEqualTo(
                first.transactionId()
        );

        assertThat(
                first.reference()
        ).isEqualTo(
                "payout_settle:c1_payout_settle_001"
        );

        assertThat(
                retry.reference()
        ).isEqualTo(
                first.reference()
        );
    }
    @Test
    void rejectsPayoutSettlementWhenHoldIsInsufficient() {

        /*
         * Fund bank + customer with €50.
         */
        ledgerProvider.recordIncomingPayment(
                INSUFFICIENT_HOLD_SETTLEMENT_USER,
                "hold_test_bank",
                LedgerCurrency.EUR,
                5_000,
                "c1_hold_test_funding_001"
        );

        /*
         * Reserve only €20.
         */
        ledgerProvider.reservePayout(
                INSUFFICIENT_HOLD_SETTLEMENT_USER,
                LedgerCurrency.EUR,
                2_000,
                "c1_insufficient_hold_settle_001"
        );

        /*
         * Attempt to settle €30.
         *
         * Bank has €50, but hold only has €20.
         */
        assertThatThrownBy(
                () -> ledgerProvider
                        .settlePayout(
                                INSUFFICIENT_HOLD_SETTLEMENT_USER,
                                "hold_test_bank",
                                LedgerCurrency.EUR,
                                3_000,
                                "c1_insufficient_hold_settle_001"
                        )
        )
                .isInstanceOf(
                        LedgerTransactionRejectedException.class
                );

        /*
         * If settlement was really atomic, the failed €30
         * settlement did not consume the existing €20 hold.
         *
         * Therefore the complete €20 must still be releasable.
         */
        LedgerOperationResult release =
                ledgerProvider.releasePayout(
                        INSUFFICIENT_HOLD_SETTLEMENT_USER,
                        LedgerCurrency.EUR,
                        2_000,
                        "c1_insufficient_hold_settle_001"
                );

        assertThat(
                release.transactionId()
        ).isPositive();
    }
    @Test
    void rejectsPayoutSettlementWhenBankBalanceIsInsufficient() {

        /*
         * Test-only funding.
         *
         * We deliberately fund the customer without funding
         * "empty_settlement_bank".
         */
        seedAvailableBalance(
                INSUFFICIENT_BANK_SETTLEMENT_USER,
                LedgerCurrency.EUR,
                2_000,
                "c1-bank-insufficient-user-seed-001"
        );

        /*
         * Reserve €10 successfully.
         */
        LedgerOperationResult reserve =
                ledgerProvider.reservePayout(
                        INSUFFICIENT_BANK_SETTLEMENT_USER,
                        LedgerCurrency.EUR,
                        1_000,
                        "c1_insufficient_bank_settle_001"
                );

        assertThat(
                reserve.transactionId()
        ).isPositive();

        /*
         * This bank account has never been funded.
         *
         * Therefore:
         *
         * bank -> world
         *
         * must fail.
         */
        assertThatThrownBy(
                () -> ledgerProvider
                        .settlePayout(
                                INSUFFICIENT_BANK_SETTLEMENT_USER,
                                "empty_settlement_bank",
                                LedgerCurrency.EUR,
                                1_000,
                                "c1_insufficient_bank_settle_001"
                        )
        )
                .isInstanceOf(
                        LedgerTransactionRejectedException.class
                );

        /*
         * Again prove atomicity.
         *
         * If the failed settlement had consumed the hold before
         * discovering the empty bank account, this release would fail.
         */
        LedgerOperationResult release =
                ledgerProvider.releasePayout(
                        INSUFFICIENT_BANK_SETTLEMENT_USER,
                        LedgerCurrency.EUR,
                        1_000,
                        "c1_insufficient_bank_settle_001"
                );

        assertThat(
                release.transactionId()
        ).isPositive();
    }
    @Test
    void reconcilesIncomingReserveAndSettlementBalances() {

        String suffix =
                UUID.randomUUID()
                        .toString()
                        .replace(
                                "-",
                                ""
                        );

        UUID userId =
                UUID.randomUUID();

        String bankId =
                "recon_"
                        + suffix;

        String fundingOperationId =
                "recon_funding_"
                        + suffix;

        String payoutOperationId =
                "recon_payout_"
                        + suffix;

        LedgerAccountAddress bankMaster =
                LedgerAccountAddressFactory
                        .bankMaster(
                                bankId,
                                LedgerCurrency.EUR
                        );

        LedgerAccountAddress available =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                LedgerCurrency.EUR
                        );

        LedgerAccountAddress hold =
                LedgerAccountAddressFactory
                        .customerHold(
                                userId,
                                LedgerCurrency.EUR,
                                payoutOperationId
                        );

        LedgerAccountAddress control =
                LedgerAccountAddressFactory
                        .safeguardedControl(
                                LedgerCurrency.EUR
                        );

        /*
         * The control account is global and already contains
         * balances from previous integration tests.
         *
         * Therefore we test its DELTA, not its absolute balance.
         */
        BigInteger controlBefore =
                balance(
                        control
                );

        /*
         * --------------------------------------------------------
         * 1. €100 confirmed incoming payment
         * --------------------------------------------------------
         */

        ledgerProvider.recordIncomingPayment(
                userId,
                bankId,
                LedgerCurrency.EUR,
                10_000,
                fundingOperationId
        );

        assertThat(
                balance(bankMaster)
        ).isEqualTo(
                BigInteger.valueOf(
                        10_000
                )
        );

        assertThat(
                balance(available)
        ).isEqualTo(
                BigInteger.valueOf(
                        10_000
                )
        );

        assertThat(
                balance(control)
        ).isEqualTo(
                controlBefore.subtract(
                        BigInteger.valueOf(
                                10_000
                        )
                )
        );

        /*
         * --------------------------------------------------------
         * 2. Reserve €30
         * --------------------------------------------------------
         */

        ledgerProvider.reservePayout(
                userId,
                LedgerCurrency.EUR,
                3_000,
                payoutOperationId
        );

        assertThat(
                balance(bankMaster)
        ).isEqualTo(
                BigInteger.valueOf(
                        10_000
                )
        );

        assertThat(
                balance(available)
        ).isEqualTo(
                BigInteger.valueOf(
                        7_000
                )
        );

        assertThat(
                balance(hold)
        ).isEqualTo(
                BigInteger.valueOf(
                        3_000
                )
        );

        assertThat(
                balance(control)
        ).isEqualTo(
                controlBefore.subtract(
                        BigInteger.valueOf(
                                10_000
                        )
                )
        );

        /*
         * During reservation:
         *
         * bank = available + holds
         */
        assertThat(
                balance(bankMaster)
        ).isEqualTo(
                balance(available)
                        .add(
                                balance(hold)
                        )
        );

        /*
         * --------------------------------------------------------
         * 3. Sponsor bank confirms €30 payout
         * --------------------------------------------------------
         */

        ledgerProvider.settlePayout(
                userId,
                bankId,
                LedgerCurrency.EUR,
                3_000,
                payoutOperationId
        );

        BigInteger finalBank =
                balance(
                        bankMaster
                );

        BigInteger finalAvailable =
                balance(
                        available
                );

        BigInteger finalHold =
                balance(
                        hold
                );

        BigInteger finalControl =
                balance(
                        control
                );

        assertThat(
                finalBank
        ).isEqualTo(
                BigInteger.valueOf(
                        7_000
                )
        );

        assertThat(
                finalAvailable
        ).isEqualTo(
                BigInteger.valueOf(
                        7_000
                )
        );

        assertThat(
                finalHold
        ).isEqualTo(
                BigInteger.ZERO
        );

        assertThat(
                finalControl
        ).isEqualTo(
                controlBefore.subtract(
                        BigInteger.valueOf(
                                7_000
                        )
                )
        );

        /*
         * --------------------------------------------------------
         * Safeguarding reconciliation
         * --------------------------------------------------------
         *
         * Bank cash represented by this isolated scenario:
         *
         *      €70
         *
         * Customer obligations:
         *
         *      €70 available
         *    + €0 holds
         *      ----------
         *      €70
         */

        assertThat(
                finalBank
        ).isEqualTo(
                finalAvailable.add(
                        finalHold
                )
        );

        /*
         * The scenario changed the global safeguarded control
         * account by exactly -€70.
         */
        BigInteger controlDelta =
                finalControl.subtract(
                        controlBefore
                );

        assertThat(
                controlDelta
        ).isEqualTo(
                finalBank.negate()
        );
    }
    @Test
    void reconcilesUnattributedIncomingPaymentThroughAttribution() {

        String suffix =
                UUID.randomUUID()
                        .toString()
                        .replace(
                                "-",
                                ""
                        );

        UUID userId =
                UUID.randomUUID();

        String bankId =
                "suspense_recon_"
                        + suffix;

        String incomingOperationId =
                "unknown_recon_"
                        + suffix;

        String attributionOperationId =
                "attribute_recon_"
                        + suffix;

        LedgerAccountAddress bankMaster =
                LedgerAccountAddressFactory
                        .bankMaster(
                                bankId,
                                LedgerCurrency.EUR
                        );

        LedgerAccountAddress suspense =
                LedgerAccountAddressFactory
                        .incomingSuspense(
                                LedgerCurrency.EUR
                        );

        LedgerAccountAddress available =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                LedgerCurrency.EUR
                        );

        LedgerAccountAddress control =
                LedgerAccountAddressFactory
                        .safeguardedControl(
                                LedgerCurrency.EUR
                        );

        BigInteger suspenseBefore =
                balance(
                        suspense
                );

        BigInteger controlBefore =
                balance(
                        control
                );

        /*
         * Unknown €50 arrives.
         */
        ledgerProvider.recordUnattributedIncomingPayment(
                bankId,
                LedgerCurrency.EUR,
                5_000,
                incomingOperationId
        );

        assertThat(
                balance(bankMaster)
        ).isEqualTo(
                BigInteger.valueOf(
                        5_000
                )
        );

        assertThat(
                balance(suspense)
        ).isEqualTo(
                suspenseBefore.add(
                        BigInteger.valueOf(
                                5_000
                        )
                )
        );

        assertThat(
                balance(control)
        ).isEqualTo(
                controlBefore.subtract(
                        BigInteger.valueOf(
                                5_000
                        )
                )
        );

        /*
         * Beneficiary identified.
         */
        ledgerProvider.attributeIncomingPayment(
                userId,
                LedgerCurrency.EUR,
                5_000,
                attributionOperationId
        );

        assertThat(
                balance(bankMaster)
        ).isEqualTo(
                BigInteger.valueOf(
                        5_000
                )
        );

        assertThat(
                balance(suspense)
        ).isEqualTo(
                suspenseBefore
        );

        assertThat(
                balance(available)
        ).isEqualTo(
                BigInteger.valueOf(
                        5_000
                )
        );

        assertThat(
                balance(control)
        ).isEqualTo(
                controlBefore.subtract(
                        BigInteger.valueOf(
                                5_000
                        )
                )
        );

        /*
         * The bank was credited only once.
         *
         * Attribution merely moved:
         *
         * suspense -> customer
         */
        assertThat(
                balance(bankMaster)
        ).isEqualTo(
                balance(available)
        );

        BigInteger controlDelta =
                balance(control)
                        .subtract(
                                controlBefore
                        );

        assertThat(
                controlDelta
        ).isEqualTo(
                balance(bankMaster)
                        .negate()
        );
    }
}