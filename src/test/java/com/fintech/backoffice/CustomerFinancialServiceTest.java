package com.fintech.backoffice;

import com.fintech.identity.CustomerDirectoryIdentityRow;
import com.fintech.identity.CustomerDirectoryLookup;
import com.fintech.ledger.CustomerFinancialRead;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerFinancialServiceTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final CustomerDirectoryLookup customers = mock(CustomerDirectoryLookup.class);
    private final CustomerFinancialRead ledger = mock(CustomerFinancialRead.class);
    private final CustomerFinancialService service = new CustomerFinancialService(customers, ledger);

    private void present(UUID userId) {
        when(customers.find(userId)).thenReturn(Optional.of(new CustomerDirectoryIdentityRow(
                userId, "synthetic@review.invalid", "ACTIVE", Instant.EPOCH)));
    }

    @Test
    void missingCustomerNeverCallsLedger() {
        assertThrows(CustomerNotFoundException.class, () -> service.wallets(A));
        assertThrows(CustomerNotFoundException.class,
                () -> service.transactions(A, "EUR", 20, null));
        verifyNoInteractions(ledger);
    }

    @Test
    void cursorIsBoundToCustomerCurrencyAndPageSize() {
        present(A);
        present(B);
        when(ledger.transactions(A, "EUR", 20, null)).thenReturn(
                new CustomerFinancialRead.TransactionPage(List.of(), true, "provider-secret"));
        String cursor = service.transactions(A, "EUR", 20, null).nextCursor();
        assertNotNull(cursor);
        assertThrows(IllegalArgumentException.class,
                () -> service.transactions(B, "EUR", 20, cursor));
        assertThrows(IllegalArgumentException.class,
                () -> service.transactions(A, "TND", 20, cursor));
        assertThrows(IllegalArgumentException.class,
                () -> service.transactions(A, "EUR", 21, cursor));
        assertThrows(IllegalArgumentException.class,
                () -> service.transactions(A, "EUR", 20, cursor + "x"));
        verify(ledger, times(1)).transactions(eq(A), eq("EUR"), eq(20), isNull());
        when(ledger.transactions(A, "EUR", 20, "provider-secret")).thenReturn(
                new CustomerFinancialRead.TransactionPage(List.of(), false, null));
        assertNull(service.transactions(A, "EUR", 20, cursor).nextCursor());
    }

    @Test
    void rejectsPageBoundsAndUnsupportedCurrencyBeforeProviderCall() {
        present(A);
        for (int size : List.of(0, 101))
            assertThrows(IllegalArgumentException.class,
                    () -> service.transactions(A, "EUR", size, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.transactions(A, "JPY", 20, null));
        verifyNoInteractions(ledger);
    }
}
