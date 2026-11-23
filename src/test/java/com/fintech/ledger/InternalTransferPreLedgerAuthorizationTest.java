
package com.fintech.ledger;

import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.FinancialTransactionNotificationService;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalTransferPreLedgerAuthorizationTest {

    @Test
    void authorizationDenialNeverCallsFormance() {

        LedgerProvider ledger =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notifications =
                mock(FinancialTransactionNotificationService.class);

        CustomerDebitAuthorization authorization =
                mock(CustomerDebitAuthorization.class);

        InternalTransferService transfers =
                new InternalTransferService(
                        ledger,
                        notifications,
                        authorization
                );

        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();

        doThrow(new AccessDeniedException(
                "Account suspended"
        )).when(authorization)
                .requireEligible(sender);

        assertThrows(
                TransferAuthorizationDeniedBeforePostingException.class,
                () -> transfers.transfer(
                        sender,
                        recipient,
                        LedgerCurrency.EUR,
                        5_000L,
                        "preledger_denial_test"
                )
        );

        // Critical: no financial operation took place.
        verifyNoInteractions(ledger, notifications);
    }
}
