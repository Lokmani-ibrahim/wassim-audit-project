package com.fintech.notifications;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.ledger.SyntheticCreditService;
import com.fintech.ledger.WalletProvisioningService;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
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
import org.junit.jupiter.api.Assumptions;
import org.springframework.core.env.Environment;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = {
                "neos.backoffice.synthetic-credit.enabled=true",
                "neos.notifications.microsoft-graph.enabled=false"
        }
)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_IT",
        matches = "true"
)
class SyntheticCreditNotificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SyntheticCreditService syntheticCreditService;

    @Autowired
    private WalletProvisioningService walletProvisioningService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationOutboxRepository outboxRepository;
    @Autowired
    private Environment environment;

    @Test
    void successfulSyntheticCreditCreatesExactlyOneDurableNotification()
            throws Exception {

        String suffix =
                UUID.randomUUID()
                        .toString()
                        .replace(
                                "-",
                                ""
                        );

        String subject =
                "c4-notification-"
                        + suffix;

        String email =
                "c4.notification."
                        + suffix
                        + "@neos.local";


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

        /*
         * --------------------------------------------------------
         * 1. CREATE A REAL PERSISTED CUSTOMER
         * --------------------------------------------------------
         */
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
                                          "installationId": "c4-notification-%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """
                                                .formatted(
                                                        phoneNumber,
                                                        suffix
                                                )
                                )
                )
                .andExpect(
                        status().isOk()
                );

        UserEntity user =
                userRepository
                        .findByKeycloakSubject(
                                subject
                        )
                        .orElseThrow();

        /*
         * Arrange the wallet state expected after C2.
         */
        walletProvisioningService
                .provisionCustomerWallets(
                        user.getId()
                );

        LedgerCurrency currency =
                LedgerCurrency.EUR;

        long amountMinor =
                10_000L;

        String operationId =
                "c4_notification_"
                        + suffix;

        /*
         * --------------------------------------------------------
         * 2. REAL FORMANCE CREDIT
         * --------------------------------------------------------
         */
        LedgerOperationResult first =
                syntheticCreditService.credit(
                        user.getId(),
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

        /*
         * --------------------------------------------------------
         * 3. VERIFY REAL POSTGRESQL OUTBOX
         * --------------------------------------------------------
         *
         * NotificationOutboxService uses this as the durable
         * idempotency key:
         *
         * TRANSACTION_COMPLETED:
         * transactionId:
         * userId:
         * EMAIL
         */
        String notificationKey =
                "TRANSACTION_COMPLETED:"
                        + first.transactionId()
                        + ":"
                        + user.getId()
                        + ":EMAIL";

        NotificationOutboxEntity stored =
                outboxRepository
                        .findByNotificationKey(
                                notificationKey
                        )
                        .orElseThrow();

        assertThat(
                stored.getNotificationKey()
        ).isEqualTo(
                notificationKey
        );

        assertThat(
                stored.getUserId()
        ).isEqualTo(
                user.getId()
        );

        assertThat(
                stored.getRecipient()
        ).isEqualTo(
                email
        );

        assertThat(
                stored.getChannel()
        ).isEqualTo(
                NotificationChannel.EMAIL
        );

        assertThat(
                stored.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.PENDING
        );

        assertThat(
                stored.getAttemptCount()
        ).isZero();

        assertThat(
                stored.getPayloadJson()
        )
                .contains(
                        "\"transactionId\":"
                                + first.transactionId()
                )
                .contains(
                        "\"type\":\"INCOMING_PAYMENT\""
                )
                .contains(
                        "\"direction\":\"CREDIT\""
                )
                .contains(
                        "\"currency\":\"EUR\""
                )
                .contains(
                        "\"amountMinor\":10000"
                );

        /*
         * --------------------------------------------------------
         * 4. RETRY THE SAME BUSINESS OPERATION
         * --------------------------------------------------------
         *
         * Formance must return the original transaction.
         */
        LedgerOperationResult retry =
                syntheticCreditService.credit(
                        user.getId(),
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
         * --------------------------------------------------------
         * 5. OUTBOX MUST ALSO REMAIN IDEMPOTENT
         * --------------------------------------------------------
         *
         * SyntheticCreditService invokes enqueueSafely again,
         * but the same Formance transaction ID produces the same
         * notification key.
         *
         * Therefore PostgreSQL must still contain exactly ONE row.
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
                stored.getId()
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
}