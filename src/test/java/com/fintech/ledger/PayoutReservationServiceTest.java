
package com.fintech.ledger;

import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PayoutReservationServiceTest {

    private LedgerProvider ledgerProvider;
    private CustomerDebitAuthorization authorization;
    private PayoutReservationService service;

    private UUID userId;

    @BeforeEach
    void setup() {

        ledgerProvider = mock(LedgerProvider.class);

        authorization = mock(
                CustomerDebitAuthorization.class
        );

        service = new PayoutReservationService(
                ledgerProvider,
                authorization
        );

        userId = UUID.randomUUID();
    }

    @Test
    void eligibleCustomerCanReservePayout() {

        LedgerOperationResult expected =
                new LedgerOperationResult(
                        100L,
                        "payout_reserve:payout_001"
                );

        when(ledgerProvider.reservePayout(
                userId,
                LedgerCurrency.EUR,
                5_000L,
                "payout_001"
        )).thenReturn(expected);

        LedgerOperationResult result = service.reserve(
                userId,
                LedgerCurrency.EUR,
                5_000L,
                " payout_001 "
        );

        assertEquals(expected, result);

        // Authorization must precede the financial operation.
        var order = inOrder(
                authorization,
                ledgerProvider
        );

        order.verify(authorization)
                .requireEligible(userId);

        order.verify(ledgerProvider)
                .reservePayout(
                        userId,
                        LedgerCurrency.EUR,
                        5_000L,
                        "payout_001"
                );

        order.verifyNoMoreInteractions();
    }

    @Test
    void suspendedCustomerCannotReservePayout() {

        doThrow(new AccessDeniedException(
                "Customer account suspended"
        ))
                .when(authorization)
                .requireEligible(userId);

        assertThrows(
                AccessDeniedException.class,
                () -> service.reserve(
                        userId,
                        LedgerCurrency.EUR,
                        5_000L,
                        "blocked_payout_001"
                )
        );

        verify(authorization)
                .requireEligible(userId);

        // CRITICAL: no financial movement.
        verifyNoInteractions(ledgerProvider);
    }

    @Test
    void zeroAmountIsRejectedBeforeAuthorization() {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.reserve(
                        userId,
                        LedgerCurrency.EUR,
                        0L,
                        "payout_002"
                )
        );

        verifyNoInteractions(
                authorization,
                ledgerProvider
        );
    }

    @Test
    void blankOperationIdIsRejected() {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.reserve(
                        userId,
                        LedgerCurrency.EUR,
                        5_000L,
                        "   "
                )
        );

        verifyNoInteractions(
                authorization,
                ledgerProvider
        );
    }

    @Test
    void ledgerFailureIsPropagated() {

        when(ledgerProvider.reservePayout(
                userId,
                LedgerCurrency.EUR,
                5_000L,
                "payout_003"
        )).thenThrow(
                new IllegalStateException(
                        "Simulated Formance outage"
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.reserve(
                        userId,
                        LedgerCurrency.EUR,
                        5_000L,
                        "payout_003"
                )
        );

        verify(authorization)
                .requireEligible(userId);

        verify(ledgerProvider)
                .reservePayout(
                        userId,
                        LedgerCurrency.EUR,
                        5_000L,
                        "payout_003"
                );
    }
}
