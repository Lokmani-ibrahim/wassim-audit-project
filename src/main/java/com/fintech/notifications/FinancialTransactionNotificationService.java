package com.fintech.notifications;

import com.fintech.identity.UserContactLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class FinancialTransactionNotificationService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    FinancialTransactionNotificationService.class
            );

    private final UserContactLookup userContactLookup;

    private final NotificationOutboxService outboxService;

    public FinancialTransactionNotificationService(
            UserContactLookup userContactLookup,
            NotificationOutboxService outboxService
    ) {

        this.userContactLookup =
                Objects.requireNonNull(
                        userContactLookup,
                        "userContactLookup must not be null"
                );

        this.outboxService =
                Objects.requireNonNull(
                        outboxService,
                        "outboxService must not be null"
                );
    }

    public void enqueueSafely(
            UUID userId,
            long transactionId,
            String reference,
            TransactionNotificationType type,
            TransactionNotificationDirection direction,
            String currency,
            String asset,
            long amountMinor
    ) {

        try {

            Objects.requireNonNull(
                    userId,
                    "userId must not be null"
            );

            Objects.requireNonNull(
                    type,
                    "type must not be null"
            );

            Objects.requireNonNull(
                    direction,
                    "direction must not be null"
            );

            if (transactionId < 0) {
                throw new IllegalArgumentException(
                        "transactionId must be nonnegative"
                );
            }

            if (amountMinor <= 0) {
                throw new IllegalArgumentException(
                        "amountMinor must be positive"
                );
            }

            if (currency == null
                    || currency.isBlank()) {

                throw new IllegalArgumentException(
                        "currency must not be blank"
                );
            }

            if (asset == null
                    || asset.isBlank()) {

                throw new IllegalArgumentException(
                        "asset must not be blank"
                );
            }

            String recipient =
                    userContactLookup
                            .findEmail(
                                    userId
                            )
                            .orElse(null);

            if (recipient == null) {

                log.warn(
                        "Skipping transaction notification because no email exists for userId={} transactionId={}",
                        userId,
                        transactionId
                );

                return;
            }

            TransactionNotificationPayload payload =
                    new TransactionNotificationPayload(
                            userId,
                            transactionId,
                            type,
                            direction,
                            currency.trim(),
                            asset.trim(),
                            amountMinor,
                            reference,
                            Instant.now()
                    );

            outboxService.enqueueTransactionEmail(
                    recipient,
                    payload
            );

        } catch (RuntimeException exception) {

            /*
             * Financial processing already succeeded before
             * this method is invoked.
             *
             * A notification persistence problem must therefore
             * never turn a successful money movement into an
             * apparent financial failure.
             */
            log.error(
                    "Unable to enqueue transaction notification safely for userId={} transactionId={}",
                    userId,
                    transactionId,
                    exception
            );
        }
    }
}