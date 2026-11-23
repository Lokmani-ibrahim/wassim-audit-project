package com.fintech.ledger;

import com.fintech.ledger.adapter.formance.FormanceAccountData;
import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddress;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigInteger;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringJUnitConfig(classes = {
        LedgerConfiguration.class,
        FormanceClient.class,
        WalletProvisioningService.class
})
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_IT",
        matches = "true"
)
class WalletProvisioningIntegrationTest {

    @Autowired
    private WalletProvisioningService walletProvisioningService;

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
    void provisionsAllCustomerWalletsWithZeroBalancesAndIsIdempotent() {

        UUID userId =
                UUID.randomUUID();

        assertThatCode(
                () -> walletProvisioningService
                        .provisionCustomerWallets(
                                userId
                        )
        ).doesNotThrowAnyException();

        /*
         * Retry the exact same provisioning.
         *
         * Every account metadata write uses a stable
         * idempotency key.
         */
        assertThatCode(
                () -> walletProvisioningService
                        .provisionCustomerWallets(
                                userId
                        )
        ).doesNotThrowAnyException();

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