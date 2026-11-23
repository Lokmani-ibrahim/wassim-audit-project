package com.fintech.notifications;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.ledger.PayoutSettlementService;
import com.fintech.ledger.WalletProvisioningService;
import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddress;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.entity.NotificationOutboxEntity;
import com.fintech.notifications.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;

import java.math.BigInteger;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = {
                "neos.notifications.microsoft-graph.enabled=false",
                "neos.notifications.dispatcher.enabled=false"
        }
)

@AutoConfigureMockMvc
@Import(PayoutSettlementService.class)
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_IT",
        matches = "true"
)
class PayoutSettlementNotificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletProvisioningService walletProvisioningService;

    @Autowired
    private LedgerProvider ledgerProvider;

    @Autowired
    private PayoutSettlementService payoutSettlementService;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private FormanceClient formanceClient;

    @Test
    void settledPayoutCreatesOneDebitNotificationAndIsIdempotent()
            throws Exception {

        String suffix =
                UUID.randomUUID()
                        .toString()
                        .replace(
                                "-",
                                ""
                        );

        UserEntity user =
                createCustomer(
                        "payout-user-" + suffix,
                        "payout."
                                + suffix
                                + "@neos.local",
                        "payout-device-" + suffix
                );

        LedgerCurrency currency =
                LedgerCurrency.EUR;

        String bankId =
                "payout_bank_"
                        + suffix;

        walletProvisioningService
                .provisionCustomerWallets(
                        user.getId()
                );

        /*
         * ========================================================
         * 1. FUND CUSTOMER + SPONSOR BANK
         * ========================================================
         */

        long fundingAmountMinor =
                10_000L;

        ledgerProvider.recordIncomingPayment(
                user.getId(),
                bankId,
                currency,
                fundingAmountMinor,
                "payout_funding_" + suffix
        );

        LedgerAccountAddress available =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                user.getId(),
                                currency
                        );

        LedgerAccountAddress bank =
                LedgerAccountAddressFactory
                        .bankMaster(
                                bankId,
                                currency
                        );

        LedgerAccountAddress control =
                LedgerAccountAddressFactory
                        .safeguardedControl(
                                currency
                        );

        /*
         * ========================================================
         * 2. RESERVE PAYOUT
         * ========================================================
         *
         * Reservation is NOT a completed customer transaction.
         * Therefore it must not create a payout-completed email.
         */

        long payoutAmountMinor =
                3_000L;

        String operationId =
                "payout_"
                        + suffix;

        LedgerOperationResult reserve =
                ledgerProvider.reservePayout(
                        user.getId(),
                        currency,
                        payoutAmountMinor,
                        operationId
                );

        assertThat(
                reserve.transactionId()
        ).isPositive();

        assertThat(
                reserve.reference()
        ).isEqualTo(
                "payout_reserve:"
                        + operationId
        );

        LedgerAccountAddress hold =
                LedgerAccountAddressFactory
                        .customerHold(
                                user.getId(),
                                currency,
                                operationId
                        );

        assertThat(
                balance(
                        available
                )
        ).isEqualTo(
                BigInteger.valueOf(
                        fundingAmountMinor
                                - payoutAmountMinor
                )
        );

        assertThat(
                balance(
                        hold
                )
        ).isEqualTo(
                BigInteger.valueOf(
                        payoutAmountMinor
                )
        );

        BigInteger bankBeforeSettlement =
                balance(
                        bank
                );

        BigInteger controlBeforeSettlement =
                balance(
                        control
                );

        BigInteger availableBeforeSettlement =
                balance(
                        available
                );

        /*
         * ========================================================
         * 3. REAL PAYOUT SETTLEMENT
         * ========================================================
         */

        LedgerOperationResult first =
                payoutSettlementService.settle(
                        user.getId(),
                        bankId,
                        currency,
                        payoutAmountMinor,
                        operationId
                );

        assertThat(
                first.transactionId()
        ).isPositive();

        assertThat(
                first.reference()
        ).isEqualTo(
                "payout_settle:"
                        + operationId
        );

        /*
         * ========================================================
         * 4. VERIFY FORMANCE ACCOUNTING
         * ========================================================
         */

        BigInteger bankAfterSettlement =
                balance(
                        bank
                );

        BigInteger controlAfterSettlement =
                balance(
                        control
                );

        BigInteger availableAfterSettlement =
                balance(
                        available
                );

        BigInteger holdAfterSettlement =
                balance(
                        hold
                );

        /*
         * Real sponsor cash left the bank account.
         */
        assertThat(
                bankAfterSettlement
                        .subtract(
                                bankBeforeSettlement
                        )
        ).isEqualTo(
                BigInteger.valueOf(
                        -payoutAmountMinor
                )
        );

        /*
         * The customer's reserved balance is fully consumed.
         */
        assertThat(
                holdAfterSettlement
        ).isZero();

        /*
         * Settlement does not debit available again.
         *
         * Available was already reduced during reservePayout().
         */
        assertThat(
                availableAfterSettlement
        ).isEqualTo(
                availableBeforeSettlement
        );

        /*
         * hold -> safeguarded control
         *
         * therefore control increases by payout amount.
         */
        assertThat(
                controlAfterSettlement
                        .subtract(
                                controlBeforeSettlement
                        )
        ).isEqualTo(
                BigInteger.valueOf(
                        payoutAmountMinor
                )
        );

        /*
         * ========================================================
         * 5. VERIFY REAL OUTBOX
         * ========================================================
         */

        String notificationKey =
                notificationKey(
                        first.transactionId(),
                        user.getId()
                );

        NotificationOutboxEntity notification =
                outboxRepository
                        .findByNotificationKey(
                                notificationKey
                        )
                        .orElseThrow();

        assertThat(
                notification.getRecipient()
        ).isEqualTo(
                user.getEmail()
        );

        assertThat(
                notification.getUserId()
        ).isEqualTo(
                user.getId()
        );

        assertThat(
                notification.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.PENDING
        );

        assertThat(
                notification.getAttemptCount()
        ).isZero();

        assertThat(
                notification.getPayloadJson()
        )
                .contains(
                        "\"type\":\"PAYOUT\""
                )
                .contains(
                        "\"direction\":\"DEBIT\""
                )
                .contains(
                        "\"currency\":\"EUR\""
                )
                .contains(
                        "\"amountMinor\":3000"
                );

        /*
         * ========================================================
         * 6. IDEMPOTENT SETTLEMENT RETRY
         * ========================================================
         */

        LedgerOperationResult retry =
                payoutSettlementService.settle(
                        user.getId(),
                        bankId,
                        currency,
                        payoutAmountMinor,
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
         * Retry must not:
         *
         * - debit the sponsor bank twice
         * - consume another hold
         * - modify available
         */
        assertThat(
                balance(
                        bank
                )
        ).isEqualTo(
                bankAfterSettlement
        );

        assertThat(
                balance(
                        hold
                )
        ).isZero();

        assertThat(
                balance(
                        available
                )
        ).isEqualTo(
                availableAfterSettlement
        );

        assertThat(
                balance(
                        control
                )
        ).isEqualTo(
                controlAfterSettlement
        );

        /*
         * ========================================================
         * 7. OUTBOX IDEMPOTENCY
         * ========================================================
         */

        assertThat(
                outboxRepository.countByNotificationKey(
                        notificationKey
                )
        ).isEqualTo(
                1
        );

        NotificationOutboxEntity afterRetry =
                outboxRepository
                        .findByNotificationKey(
                                notificationKey
                        )
                        .orElseThrow();

        assertThat(
                afterRetry.getId()
        ).isEqualTo(
                notification.getId()
        );

        assertThat(
                afterRetry.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.PENDING
        );

        assertThat(
                afterRetry.getAttemptCount()
        ).isZero();
    }

    private UserEntity createCustomer(
            String subject,
            String email,
            String installationId
    ) throws Exception {

        String phoneNumber =
                "+216"
                        + String.format(
                        "%08d",
                        Math.floorMod(
                                UUID.randomUUID()
                                        .hashCode(),
                                100_000_000
                        )
                );

        mockMvc.perform(
                        post(
                                "/api/onboarding/personal"
                        )
                                .with(
                                        jwt()
                                                .jwt(token ->
                                                        token
                                                                .subject(
                                                                        subject
                                                                )
                                                                .claim(
                                                                        "email",
                                                                        email
                                                                )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_CUSTOMER"
                                                        )
                                                )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "phoneNumber": "%s",
                                          "installationId": "%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """
                                                .formatted(
                                                        phoneNumber,
                                                        installationId
                                                )
                                )
                )
                .andExpect(
                        status().isOk()
                );

        return userRepository
                .findByKeycloakSubject(
                        subject
                )
                .orElseThrow();
    }

    private String notificationKey(
            long transactionId,
            UUID userId
    ) {

        return "TRANSACTION_COMPLETED:"
                + transactionId
                + ":"
                + userId
                + ":EMAIL";
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