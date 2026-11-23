package com.fintech.notifications;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.ledger.InternalTransferService;
import com.fintech.ledger.WalletProvisioningService;
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
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_IT",
        matches = "true"
)
class InternalTransferNotificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletProvisioningService walletProvisioningService;

    @Autowired
    private LedgerProvider ledgerProvider;

    @Autowired
    private InternalTransferService internalTransferService;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private com.fintech.ledger.adapter.formance.FormanceClient
            formanceClient;

    @Test
    void internalTransferCreatesSenderAndRecipientNotificationsAndIsIdempotent()
            throws Exception {

        String suffix =
                UUID.randomUUID()
                        .toString()
                        .replace(
                                "-",
                                ""
                        );

        /*
         * ========================================================
         * 1. CREATE TWO REAL CUSTOMERS
         * ========================================================
         */

        UserEntity sender =
                createCustomer(
                        "transfer-sender-" + suffix,
                        "sender."
                                + suffix
                                + "@neos.local",
                        "sender-" + suffix
                );

        UserEntity recipient =
                createCustomer(
                        "transfer-recipient-" + suffix,
                        "recipient."
                                + suffix
                                + "@neos.local",
                        "recipient-" + suffix
                );

        assertThat(
                sender.getId()
        ).isNotEqualTo(
                recipient.getId()
        );

        /*
         * ========================================================
         * 2. PROVISION REAL FORMANCE WALLETS
         * ========================================================
         */

        walletProvisioningService
                .provisionCustomerWallets(
                        sender.getId()
                );

        walletProvisioningService
                .provisionCustomerWallets(
                        recipient.getId()
                );

        LedgerCurrency currency =
                LedgerCurrency.EUR;

        LedgerAccountAddress senderAvailable =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                sender.getId(),
                                currency
                        );

        LedgerAccountAddress recipientAvailable =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                recipient.getId(),
                                currency
                        );

        /*
         * ========================================================
         * 3. FUND THE SENDER
         * ========================================================
         *
         * We intentionally use LedgerProvider directly here.
         *
         * This test is about TRANSFER notifications.
         *
         * We do not want the funding operation itself to create
         * an application-level incoming-payment notification.
         */

        String fundingOperationId =
                "transfer_funding_"
                        + suffix;

        long fundingAmountMinor =
                20_000L;

        LedgerOperationResult funding =
                ledgerProvider.recordIncomingPayment(
                        sender.getId(),
                        "sponsor",
                        currency,
                        fundingAmountMinor,
                        fundingOperationId
                );

        assertThat(
                funding.transactionId()
        ).isPositive();

        assertThat(
                balance(
                        senderAvailable
                )
        ).isEqualTo(
                BigInteger.valueOf(
                        fundingAmountMinor
                )
        );

        assertThat(
                balance(
                        recipientAvailable
                )
        ).isZero();

        /*
         * ========================================================
         * 4. EXECUTE THE REAL INTERNAL TRANSFER
         * ========================================================
         */

        long transferAmountMinor =
                5_000L;

        String operationId =
                "internal_transfer_"
                        + suffix;

        BigInteger senderBefore =
                balance(
                        senderAvailable
                );

        BigInteger recipientBefore =
                balance(
                        recipientAvailable
                );

        LedgerOperationResult first =
                internalTransferService.transfer(
                        sender.getId(),
                        recipient.getId(),
                        currency,
                        transferAmountMinor,
                        operationId
                );

        assertThat(
                first.transactionId()
        ).isPositive();

        assertThat(
                first.reference()
        ).isEqualTo(
                "internal_transfer:"
                        + operationId
        );

        /*
         * ========================================================
         * 5. VERIFY REAL FORMANCE BALANCES
         * ========================================================
         */

        BigInteger senderAfter =
                balance(
                        senderAvailable
                );

        BigInteger recipientAfter =
                balance(
                        recipientAvailable
                );

        assertThat(
                senderAfter
                        .subtract(
                                senderBefore
                        )
        ).isEqualTo(
                BigInteger.valueOf(
                        -transferAmountMinor
                )
        );

        assertThat(
                recipientAfter
                        .subtract(
                                recipientBefore
                        )
        ).isEqualTo(
                BigInteger.valueOf(
                        transferAmountMinor
                )
        );

        /*
         * Internal transfer conserves money.
         */
        assertThat(
                senderBefore
                        .add(
                                recipientBefore
                        )
        ).isEqualTo(
                senderAfter
                        .add(
                                recipientAfter
                        )
        );

        /*
         * ========================================================
         * 6. VERIFY TWO REAL OUTBOX ROWS
         * ========================================================
         *
         * One Formance transaction has two customer perspectives:
         *
         * sender    -> DEBIT
         * recipient -> CREDIT
         */

        String senderNotificationKey =
                notificationKey(
                        first.transactionId(),
                        sender.getId()
                );

        String recipientNotificationKey =
                notificationKey(
                        first.transactionId(),
                        recipient.getId()
                );

        NotificationOutboxEntity senderNotification =
                outboxRepository
                        .findByNotificationKey(
                                senderNotificationKey
                        )
                        .orElseThrow();

        NotificationOutboxEntity recipientNotification =
                outboxRepository
                        .findByNotificationKey(
                                recipientNotificationKey
                        )
                        .orElseThrow();

        /*
         * Sender notification.
         */
        assertThat(
                senderNotification.getRecipient()
        ).isEqualTo(
                sender.getEmail()
        );

        assertThat(
                senderNotification.getUserId()
        ).isEqualTo(
                sender.getId()
        );

        assertThat(
                senderNotification.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.PENDING
        );

        assertThat(
                senderNotification.getAttemptCount()
        ).isZero();

        assertThat(
                senderNotification.getPayloadJson()
        )
                .contains(
                        "\"type\":\"TRANSFER\""
                )
                .contains(
                        "\"direction\":\"DEBIT\""
                )
                .contains(
                        "\"currency\":\"EUR\""
                )
                .contains(
                        "\"amountMinor\":5000"
                );

        /*
         * Recipient notification.
         */
        assertThat(
                recipientNotification.getRecipient()
        ).isEqualTo(
                recipient.getEmail()
        );

        assertThat(
                recipientNotification.getUserId()
        ).isEqualTo(
                recipient.getId()
        );

        assertThat(
                recipientNotification.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.PENDING
        );

        assertThat(
                recipientNotification.getAttemptCount()
        ).isZero();

        assertThat(
                recipientNotification.getPayloadJson()
        )
                .contains(
                        "\"type\":\"TRANSFER\""
                )
                .contains(
                        "\"direction\":\"CREDIT\""
                )
                .contains(
                        "\"currency\":\"EUR\""
                )
                .contains(
                        "\"amountMinor\":5000"
                );

        /*
         * The two notifications belong to the SAME financial
         * transaction but to DIFFERENT users.
         */
        assertThat(
                senderNotification.getNotificationKey()
        ).isNotEqualTo(
                recipientNotification.getNotificationKey()
        );

        /*
         * ========================================================
         * 7. RETRY THE SAME TRANSFER
         * ========================================================
         */

        LedgerOperationResult retry =
                internalTransferService.transfer(
                        sender.getId(),
                        recipient.getId(),
                        currency,
                        transferAmountMinor,
                        operationId
                );

        /*
         * Formance idempotency:
         *
         * same operation
         * ->
         * same financial transaction
         */
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
         * Retry must NOT move money twice.
         */
        assertThat(
                balance(
                        senderAvailable
                )
        ).isEqualTo(
                senderAfter
        );

        assertThat(
                balance(
                        recipientAvailable
                )
        ).isEqualTo(
                recipientAfter
        );

        /*
         * ========================================================
         * 8. VERIFY OUTBOX IDEMPOTENCY
         * ========================================================
         *
         * The application calls enqueueSafely again for both
         * customers.
         *
         * But:
         *
         * same Formance transactionId
         * + same userId
         * + same channel
         *
         * produces the same notification key.
         *
         * Therefore only two total rows remain:
         *
         * one for the sender
         * one for the recipient
         */

        assertThat(
                outboxRepository.countByNotificationKey(
                        senderNotificationKey
                )
        ).isEqualTo(
                1
        );

        assertThat(
                outboxRepository.countByNotificationKey(
                        recipientNotificationKey
                )
        ).isEqualTo(
                1
        );

        NotificationOutboxEntity senderAfterRetry =
                outboxRepository
                        .findByNotificationKey(
                                senderNotificationKey
                        )
                        .orElseThrow();

        NotificationOutboxEntity recipientAfterRetry =
                outboxRepository
                        .findByNotificationKey(
                                recipientNotificationKey
                        )
                        .orElseThrow();

        assertThat(
                senderAfterRetry.getId()
        ).isEqualTo(
                senderNotification.getId()
        );

        assertThat(
                recipientAfterRetry.getId()
        ).isEqualTo(
                recipientNotification.getId()
        );

        assertThat(
                senderAfterRetry.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.PENDING
        );

        assertThat(
                recipientAfterRetry.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.PENDING
        );
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