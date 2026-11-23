package com.fintech.notifications;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.ledger.FeeCollectionService;
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
class FeeCollectionNotificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletProvisioningService walletProvisioningService;

    @Autowired
    private LedgerProvider ledgerProvider;

    @Autowired
    private FeeCollectionService feeCollectionService;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private FormanceClient formanceClient;

    @Test
    void feeCollectionCreatesOneDebitNotificationAndIsIdempotent()
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
                        "fee-user-" + suffix,
                        "fee."
                                + suffix
                                + "@neos.local",
                        "fee-device-" + suffix
                );

        LedgerCurrency currency =
                LedgerCurrency.EUR;

        walletProvisioningService
                .provisionCustomerWallets(
                        user.getId()
                );

        /*
         * Fund directly through LedgerProvider so this setup
         * does not create an incoming-payment notification.
         */
        long fundingAmountMinor =
                10_000L;

        ledgerProvider.recordIncomingPayment(
                user.getId(),
                "sponsor",
                currency,
                fundingAmountMinor,
                "fee_funding_" + suffix
        );

        LedgerAccountAddress available =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                user.getId(),
                                currency
                        );

        LedgerAccountAddress fees =
                LedgerAccountAddressFactory
                        .platformFees(
                                currency
                        );

        BigInteger availableBefore =
                balance(
                        available
                );

        BigInteger feesBefore =
                balance(
                        fees
                );

        long feeAmountMinor =
                500L;

        String operationId =
                "fee_"
                        + suffix;

        /*
         * ========================================================
         * FIRST FEE COLLECTION
         * ========================================================
         */
        LedgerOperationResult first =
                feeCollectionService.collect(
                        user.getId(),
                        currency,
                        feeAmountMinor,
                        operationId
                );

        assertThat(
                first.transactionId()
        ).isPositive();

        assertThat(
                first.reference()
        ).isEqualTo(
                "fee_collection:"
                        + operationId
        );

        BigInteger availableAfter =
                balance(
                        available
                );

        BigInteger feesAfter =
                balance(
                        fees
                );

        assertThat(
                availableAfter
                        .subtract(
                                availableBefore
                        )
        ).isEqualTo(
                BigInteger.valueOf(
                        -feeAmountMinor
                )
        );

        assertThat(
                feesAfter
                        .subtract(
                                feesBefore
                        )
        ).isEqualTo(
                BigInteger.valueOf(
                        feeAmountMinor
                )
        );

        /*
         * Money conservation:
         *
         * customer decrease == platform fee increase.
         */
        assertThat(
                availableBefore
                        .add(
                                feesBefore
                        )
        ).isEqualTo(
                availableAfter
                        .add(
                                feesAfter
                        )
        );

        /*
         * ========================================================
         * REAL POSTGRESQL OUTBOX
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
                        "\"type\":\"FEE\""
                )
                .contains(
                        "\"direction\":\"DEBIT\""
                )
                .contains(
                        "\"currency\":\"EUR\""
                )
                .contains(
                        "\"amountMinor\":500"
                );

        /*
         * ========================================================
         * IDEMPOTENT RETRY
         * ========================================================
         */
        LedgerOperationResult retry =
                feeCollectionService.collect(
                        user.getId(),
                        currency,
                        feeAmountMinor,
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
         * Fee must not be charged twice.
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
                        fees
                )
        ).isEqualTo(
                feesAfter
        );

        /*
         * Outbox also remains idempotent.
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