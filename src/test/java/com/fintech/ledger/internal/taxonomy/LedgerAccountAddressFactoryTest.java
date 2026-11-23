package com.fintech.ledger.internal.taxonomy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerAccountAddressFactoryTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "550e8400-e29b-41d4-a716-446655440000"
            );

    @Test
    void createsCustomerAvailableAccount() {

        LedgerAccountAddress account =
                LedgerAccountAddressFactory.customerAvailable(
                        USER_ID,
                        LedgerCurrency.EUR
                );

        assertThat(account.value())
                .isEqualTo(
                        "users:"
                                + USER_ID
                                + ":wallet:EUR:available"
                );

        assertThat(account.currency())
                .isEqualTo(LedgerCurrency.EUR);

        assertThat(account.kind())
                .isEqualTo(
                        LedgerAccountKind.CUSTOMER_AVAILABLE
                );

        assertThat(account.kind().balancePolicy())
                .isEqualTo(
                        LedgerBalancePolicy.NON_NEGATIVE
                );
    }

    @Test
    void createsCustomerHoldAccount() {

        LedgerAccountAddress account =
                LedgerAccountAddressFactory.customerHold(
                        USER_ID,
                        LedgerCurrency.USD,
                        "payout_123"
                );

        assertThat(account.value())
                .isEqualTo(
                        "users:"
                                + USER_ID
                                + ":wallet:USD:holds:payout_123"
                );

        assertThat(account.currency())
                .isEqualTo(LedgerCurrency.USD);

        assertThat(account.kind())
                .isEqualTo(
                        LedgerAccountKind.CUSTOMER_HOLD
                );

        assertThat(account.kind().balancePolicy())
                .isEqualTo(
                        LedgerBalancePolicy.NON_NEGATIVE
                );
    }

    @Test
    void createsPositiveSponsorBankMasterAccount() {

        LedgerAccountAddress account =
                LedgerAccountAddressFactory.bankMaster(
                        "sponsor",
                        LedgerCurrency.EUR
                );

        assertThat(account.value())
                .isEqualTo(
                        "bank:sponsor:master:EUR"
                );

        assertThat(account.currency())
                .isEqualTo(LedgerCurrency.EUR);

        assertThat(account.kind())
                .isEqualTo(
                        LedgerAccountKind.BANK_MASTER
                );

        assertThat(account.kind().balancePolicy())
                .isEqualTo(
                        LedgerBalancePolicy.NON_NEGATIVE
                );
    }

    @Test
    void createsSafeguardedControlAccount() {

        LedgerAccountAddress account =
                LedgerAccountAddressFactory
                        .safeguardedControl(
                                LedgerCurrency.EUR
                        );

        assertThat(account.value())
                .isEqualTo(
                        "control:safeguarded:EUR"
                );

        assertThat(account.currency())
                .isEqualTo(LedgerCurrency.EUR);

        assertThat(account.kind())
                .isEqualTo(
                        LedgerAccountKind.SAFEGUARDED_CONTROL
                );

        assertThat(account.kind().balancePolicy())
                .isEqualTo(
                        LedgerBalancePolicy.NON_POSITIVE
                );
    }

    @Test
    void createsIncomingSuspenseAccount() {

        LedgerAccountAddress account =
                LedgerAccountAddressFactory
                        .incomingSuspense(
                                LedgerCurrency.GBP
                        );

        assertThat(account.value())
                .isEqualTo(
                        "suspense:incoming:GBP"
                );

        assertThat(account.currency())
                .isEqualTo(LedgerCurrency.GBP);

        assertThat(account.kind())
                .isEqualTo(
                        LedgerAccountKind.INCOMING_SUSPENSE
                );

        assertThat(account.kind().balancePolicy())
                .isEqualTo(
                        LedgerBalancePolicy.NON_NEGATIVE
                );
    }

    @Test
    void createsPlatformFeesAccount() {

        LedgerAccountAddress account =
                LedgerAccountAddressFactory
                        .platformFees(
                                LedgerCurrency.TND
                        );

        assertThat(account.value())
                .isEqualTo(
                        "platform:fees:TND"
                );

        assertThat(account.currency())
                .isEqualTo(LedgerCurrency.TND);

        assertThat(account.kind())
                .isEqualTo(
                        LedgerAccountKind.PLATFORM_FEES
                );

        assertThat(account.kind().balancePolicy())
                .isEqualTo(
                        LedgerBalancePolicy.NON_NEGATIVE
                );
    }

    @Test
    void rejectsInvalidBankId() {

        assertThatThrownBy(
                () -> LedgerAccountAddressFactory
                        .bankMaster(
                                "bad:bank",
                                LedgerCurrency.EUR
                        )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "bankId"
                );
    }

    @Test
    void rejectsInvalidHoldOperationId() {

        assertThatThrownBy(
                () -> LedgerAccountAddressFactory
                        .customerHold(
                                USER_ID,
                                LedgerCurrency.EUR,
                                "payout:123"
                        )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "operationId"
                );
    }
}