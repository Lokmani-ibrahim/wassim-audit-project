package com.fintech.notifications;

import com.fintech.identity.UserContactLookup;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FinancialTransactionNotificationServiceTest {

    @Test
    void successfulTransactionCreatesNotificationPayload() {

        UserContactLookup userContactLookup =
                mock(UserContactLookup.class);

        NotificationOutboxService outboxService =
                mock(NotificationOutboxService.class);

        FinancialTransactionNotificationService service =
                new FinancialTransactionNotificationService(
                        userContactLookup,
                        outboxService
                );

        UUID userId = UUID.randomUUID();

        when(
                userContactLookup.findEmail(userId)
        ).thenReturn(
                Optional.of("customer@neos.local")
        );

        service.enqueueSafely(
                userId,
                91L,
                "incoming_payment:test_001",
                TransactionNotificationType.INCOMING_PAYMENT,
                TransactionNotificationDirection.CREDIT,
                "EUR",
                "EUR/2",
                10_000L
        );

        ArgumentCaptor<TransactionNotificationPayload> captor =
                ArgumentCaptor.forClass(
                        TransactionNotificationPayload.class
                );

        verify(outboxService).enqueueTransactionEmail(
                eq("customer@neos.local"),
                captor.capture()
        );

        TransactionNotificationPayload payload =
                captor.getValue();

        assertThat(payload.userId()).isEqualTo(userId);
        assertThat(payload.transactionId()).isEqualTo(91L);
        assertThat(payload.type())
                .isEqualTo(TransactionNotificationType.INCOMING_PAYMENT);
        assertThat(payload.direction())
                .isEqualTo(TransactionNotificationDirection.CREDIT);
        assertThat(payload.currency()).isEqualTo("EUR");
        assertThat(payload.asset()).isEqualTo("EUR/2");
        assertThat(payload.amountMinor()).isEqualTo(10_000L);
        assertThat(payload.reference())
                .isEqualTo("incoming_payment:test_001");
        assertThat(payload.occurredAt()).isNotNull();
    }

    @Test
    void missingEmailDoesNotCreateNotification() {

        UserContactLookup userContactLookup =
                mock(UserContactLookup.class);

        NotificationOutboxService outboxService =
                mock(NotificationOutboxService.class);

        FinancialTransactionNotificationService service =
                new FinancialTransactionNotificationService(
                        userContactLookup,
                        outboxService
                );

        UUID userId = UUID.randomUUID();

        when(
                userContactLookup.findEmail(userId)
        ).thenReturn(Optional.empty());

        assertThatCode(
                () -> service.enqueueSafely(
                        userId,
                        92L,
                        "fee_collection:test_002",
                        TransactionNotificationType.FEE,
                        TransactionNotificationDirection.DEBIT,
                        "EUR",
                        "EUR/2",
                        500L
                )
        ).doesNotThrowAnyException();

        verifyNoInteractions(outboxService);
    }

    @Test
    void outboxFailureDoesNotEscapeAfterFinancialSuccess() {

        UserContactLookup userContactLookup =
                mock(UserContactLookup.class);

        NotificationOutboxService outboxService =
                mock(NotificationOutboxService.class);

        FinancialTransactionNotificationService service =
                new FinancialTransactionNotificationService(
                        userContactLookup,
                        outboxService
                );

        UUID userId = UUID.randomUUID();

        when(
                userContactLookup.findEmail(userId)
        ).thenReturn(
                Optional.of("customer@neos.local")
        );

        doThrow(
                new RuntimeException("PostgreSQL unavailable")
        ).when(outboxService).enqueueTransactionEmail(
                eq("customer@neos.local"),
                any(TransactionNotificationPayload.class)
        );

        assertThatCode(
                () -> service.enqueueSafely(
                        userId,
                        93L,
                        "payout_settle:test_003",
                        TransactionNotificationType.PAYOUT,
                        TransactionNotificationDirection.DEBIT,
                        "EUR",
                        "EUR/2",
                        3_000L
                )
        ).doesNotThrowAnyException();

        verify(outboxService).enqueueTransactionEmail(
                eq("customer@neos.local"),
                any(TransactionNotificationPayload.class)
        );
    }
}
