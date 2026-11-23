package com.fintech.ledger;

import com.fintech.identity.IdentityLookupService;
import com.fintech.ledger.adapter.formance.*;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerTransactionHistoryBatchTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final LedgerCurrency CURRENCY = LedgerCurrency.TND;
    private final FormanceClient provider = mock(FormanceClient.class);
    private final CustomerTransactionHistoryService service = new CustomerTransactionHistoryService(
            mock(IdentityLookupService.class), provider);

    private static FormanceTransactionData transaction(long id, String operation, String operationId,
            String source, String destination, long minor, Instant at) {
        return new FormanceTransactionData(List.of(new FormanceTransactionPosting(
                source, destination, minor, "TND/3")),
                Map.of("neos.operation", operation, "neos.operation_id", operationId),
                at, operation + ":" + operationId, id, at, at, false);
    }

    @Test
    void settlesMultiplePayoutsWithOneBatchReadAndOriginalSettlementFields() {
        String available = LedgerAccountAddressFactory.customerAvailable(USER, CURRENCY).value();
        String holdA = LedgerAccountAddressFactory.customerHold(USER, CURRENCY, "payout-a").value();
        String holdB = LedgerAccountAddressFactory.customerHold(USER, CURRENCY, "payout-b").value();
        Instant reserveAt = Instant.parse("2026-11-09T10:00:00Z");
        Instant settledAt = Instant.parse("2026-11-09T11:00:00Z");
        when(provider.getTransactionsForAccount(eq(available), eq(20), isNull()))
                .thenReturn(new FormanceTransactionsResponse(new FormanceTransactionsCursor(
                        20, false, null, List.of(
                        transaction(1, "payout_reserve", "payout-a", available, holdA, 1000, reserveAt),
                        transaction(2, "payout_reserve", "payout-b", available, holdB, 2000, reserveAt)))));
        when(provider.getTransactionsForAccounts(anyList(), eq(100), isNull()))
                .thenReturn(new FormanceTransactionsResponse(new FormanceTransactionsCursor(
                        100, false, null, List.of(
                        transaction(3, "payout_settle", "payout-a", holdA, "control:TND", 1000, settledAt),
                        transaction(4, "payout_settle", "payout-b", holdB, "control:TND", 2000, settledAt)))));

        CustomerTransactionPage page = service.getHistoryForUser(USER, CURRENCY, 20, null);
        assertEquals(2, page.items().size());
        assertEquals(List.of(3L, 4L), page.items().stream().map(CustomerTransactionItem::transactionId).toList());
        assertTrue(page.items().stream().allMatch(item -> item.type() == CustomerTransactionType.PAYOUT &&
                item.direction() == CustomerTransactionDirection.DEBIT &&
                item.status() == CustomerTransactionStatus.COMPLETED && item.occurredAt().equals(settledAt)));
        assertEquals(List.of(1000L, 2000L), page.items().stream().map(CustomerTransactionItem::amountMinor).toList());
        verify(provider, times(1)).getTransactionsForAccounts(anyList(), eq(100), isNull());
        verify(provider, times(1)).getTransactionsForAccount(anyString(), anyInt(), nullable(String.class));
    }
}
