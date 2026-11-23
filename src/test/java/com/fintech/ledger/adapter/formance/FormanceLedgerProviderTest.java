package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormanceLedgerProviderTest {

    private static final UUID USER_A =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID USER_B =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    @Mock
    private FormanceClient formanceClient;

    private LedgerProvider ledgerProvider;

    @BeforeEach
    void setUp() {

        ledgerProvider =
                new FormanceLedgerProvider(
                        formanceClient
                );
    }

    @Test
    void verifyConnectionDelegatesToFormanceClient() {

        ledgerProvider.verifyConnection();

        verify(
                formanceClient
        ).verifyConnection();
    }

    @Test
    void transfersBetweenCustomerAvailableAccounts() {

        when(
                formanceClient.createTransaction(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(
                                "internal_transfer:transfer_123"
                        )
                )
        ).thenReturn(
                new FormanceTransactionResult(
                        101,
                        "internal_transfer:transfer_123"
                )
        );

        LedgerOperationResult result =
                ledgerProvider.transferBetweenUsers(
                        USER_A,
                        USER_B,
                        LedgerCurrency.EUR,
                        5_000,
                        "transfer_123"
                );

        ArgumentCaptor<FormanceCreateTransactionRequest> captor =
                ArgumentCaptor.forClass(
                        FormanceCreateTransactionRequest.class
                );

        verify(
                formanceClient
        ).createTransaction(
                captor.capture(),
                org.mockito.ArgumentMatchers.eq(
                        "internal_transfer:transfer_123"
                )
        );

        FormanceCreateTransactionRequest request =
                captor.getValue();

        assertThat(
                request.reference()
        ).isEqualTo(
                "internal_transfer:transfer_123"
        );

        assertThat(
                request.metadata()
        ).containsEntry(
                "neos.operation",
                "internal_transfer"
        ).containsEntry(
                "neos.operation_id",
                "transfer_123"
        ).containsEntry(
                "neos.currency",
                "EUR"
        );

        assertThat(
                request.postings()
        ).containsExactly(
                new FormancePosting(
                        "users:"
                                + USER_A
                                + ":wallet:EUR:available",
                        "users:"
                                + USER_B
                                + ":wallet:EUR:available",
                        "EUR/2",
                        5_000
                )
        );

        assertThat(
                result.transactionId()
        ).isEqualTo(
                101
        );

        assertThat(
                result.reference()
        ).isEqualTo(
                "internal_transfer:transfer_123"
        );
    }

    @Test
    void reservesPayoutIntoOperationSpecificHold() {

        when(
                formanceClient.createTransaction(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(
                                "payout_reserve:payout_123"
                        )
                )
        ).thenReturn(
                new FormanceTransactionResult(
                        102,
                        "payout_reserve:payout_123"
                )
        );

        ledgerProvider.reservePayout(
                USER_A,
                LedgerCurrency.USD,
                2_500,
                "payout_123"
        );

        ArgumentCaptor<FormanceCreateTransactionRequest> captor =
                ArgumentCaptor.forClass(
                        FormanceCreateTransactionRequest.class
                );

        verify(
                formanceClient
        ).createTransaction(
                captor.capture(),
                org.mockito.ArgumentMatchers.eq(
                        "payout_reserve:payout_123"
                )
        );

        assertThat(
                captor
                        .getValue()
                        .postings()
        ).containsExactly(
                new FormancePosting(
                        "users:"
                                + USER_A
                                + ":wallet:USD:available",
                        "users:"
                                + USER_A
                                + ":wallet:USD:holds:payout_123",
                        "USD/2",
                        2_500
                )
        );
    }

    @Test
    void releasesPayoutBackToAvailableBalance() {

        when(
                formanceClient.createTransaction(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(
                                "payout_release:payout_123"
                        )
                )
        ).thenReturn(
                new FormanceTransactionResult(
                        103,
                        "payout_release:payout_123"
                )
        );

        ledgerProvider.releasePayout(
                USER_A,
                LedgerCurrency.EUR,
                1_500,
                "payout_123"
        );

        ArgumentCaptor<FormanceCreateTransactionRequest> captor =
                ArgumentCaptor.forClass(
                        FormanceCreateTransactionRequest.class
                );

        verify(
                formanceClient
        ).createTransaction(
                captor.capture(),
                org.mockito.ArgumentMatchers.eq(
                        "payout_release:payout_123"
                )
        );

        assertThat(
                captor
                        .getValue()
                        .postings()
        ).containsExactly(
                new FormancePosting(
                        "users:"
                                + USER_A
                                + ":wallet:EUR:holds:payout_123",
                        "users:"
                                + USER_A
                                + ":wallet:EUR:available",
                        "EUR/2",
                        1_500
                )
        );
    }

    @Test
    void collectsFeeFromCustomerAvailableBalance() {

        when(
                formanceClient.createTransaction(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(
                                "fee_collection:fee_123"
                        )
                )
        ).thenReturn(
                new FormanceTransactionResult(
                        104,
                        "fee_collection:fee_123"
                )
        );

        ledgerProvider.collectFee(
                USER_A,
                LedgerCurrency.TND,
                1_250,
                "fee_123"
        );

        ArgumentCaptor<FormanceCreateTransactionRequest> captor =
                ArgumentCaptor.forClass(
                        FormanceCreateTransactionRequest.class
                );

        verify(
                formanceClient
        ).createTransaction(
                captor.capture(),
                org.mockito.ArgumentMatchers.eq(
                        "fee_collection:fee_123"
                )
        );

        assertThat(
                captor
                        .getValue()
                        .postings()
        ).containsExactly(
                new FormancePosting(
                        "users:"
                                + USER_A
                                + ":wallet:TND:available",
                        "platform:fees:TND",
                        "TND/3",
                        1_250
                )
        );
    }

    @Test
    void rejectsTransferToSameCustomer() {

        assertThatThrownBy(
                () -> ledgerProvider
                        .transferBetweenUsers(
                                USER_A,
                                USER_A,
                                LedgerCurrency.EUR,
                                100,
                                "transfer_123"
                        )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "must be different"
                );
    }

    @Test
    void rejectsZeroAmount() {

        assertThatThrownBy(
                () -> ledgerProvider
                        .collectFee(
                                USER_A,
                                LedgerCurrency.EUR,
                                0,
                                "fee_123"
                        )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "amountMinor must be positive"
                );
    }

    @Test
    void rejectsInvalidOperationId() {

        assertThatThrownBy(
                () -> ledgerProvider
                        .reservePayout(
                                USER_A,
                                LedgerCurrency.EUR,
                                100,
                                "payout:123"
                        )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "letters, numbers"
                );
    }
}