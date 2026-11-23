package com.fintech.ledger;

import com.fintech.kyc.KycApprovedEvent;
import com.fintech.ledger.adapter.formance.FormanceAccountData;
import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddress;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import com.fintech.kyc.KycWalletProvisioningEligibility;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.Mockito.when;
import java.math.BigInteger;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringJUnitConfig(classes = {
        LedgerConfiguration.class,
        FormanceClient.class,
        WalletProvisioningService.class,
        WalletProvisioningOnKycApproved.class
})
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_IT",
        matches = "true"
)
class WalletProvisioningOnKycApprovedIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private FormanceClient formanceClient;

    @MockitoBean
    private KycWalletProvisioningEligibility eligibility;
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
    void kycApprovedEventProvisionsAllCustomerWalletsAndIsIdempotent() {

        UUID userId =
                UUID.randomUUID();
        when(eligibility.isEligible(userId))
                .thenReturn(true);

        /*
         * First APPROVED event.
         */
        assertThatCode(
                () -> eventPublisher.publishEvent(
                        new KycApprovedEvent(
                                userId
                        )
                )
        ).doesNotThrowAnyException();

        verifyAllWallets(
                userId
        );

        /*
         * Simulate a duplicate provider webhook /
         * duplicate APPROVED notification.
         *
         * Wallet provisioning must remain idempotent.
         */
        assertThatCode(
                () -> eventPublisher.publishEvent(
                        new KycApprovedEvent(
                                userId
                        )
                )
        ).doesNotThrowAnyException();

        verifyAllWallets(
                userId
        );
    }

    private void verifyAllWallets(
            UUID userId
    ) {

        assertThat(
                LedgerCurrency.values()
        ).hasSize(
                6
        );

        for (LedgerCurrency currency
                : LedgerCurrency.values()) {

            LedgerAccountAddress account =
                    LedgerAccountAddressFactory
                            .customerAvailable(
                                    userId,
                                    currency
                            );

            FormanceAccountData actual =
                    formanceClient.getAccount(
                            account.value()
                    );

            assertThat(
                    actual.address()
            ).isEqualTo(
                    account.value()
            );

            assertThat(
                    actual.metadata()
            )
                    .containsEntry(
                            "neos.account_kind",
                            "customer_available"
                    )
                    .containsEntry(
                            "neos.user_id",
                            userId.toString()
                    )
                    .containsEntry(
                            "neos.currency",
                            currency.currencyCode()
                    )
                    .containsEntry(
                            "neos.wallet_status",
                            "ACTIVE"
                    );

            /*
             * Wallet creation must never manufacture money.
             */
            assertThat(
                    formanceClient.getAccountBalance(
                            account.value(),
                            currency.formanceAsset()
                    )
            ).isEqualTo(
                    BigInteger.ZERO
            );
        }
    }
}