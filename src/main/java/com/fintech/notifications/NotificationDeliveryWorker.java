package com.fintech.notifications;

import com.fintech.notifications.port.EmailNotificationPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Service
public class NotificationDeliveryWorker {

    private static final int MAX_ATTEMPTS = 5;

    private static final Duration DEFAULT_RETRY_DELAY =
            Duration.ofSeconds(30);

    private final NotificationOutboxClaimService claimService;
    private final ObjectProvider<EmailNotificationPort> emailPortProvider;
    private final JsonMapper jsonMapper;

    public NotificationDeliveryWorker(
            NotificationOutboxClaimService claimService,
            ObjectProvider<EmailNotificationPort> emailPortProvider,
            JsonMapper jsonMapper
    ) {

        this.claimService =
                Objects.requireNonNull(
                        claimService,
                        "claimService must not be null"
                );

        this.emailPortProvider =
                Objects.requireNonNull(
                        emailPortProvider,
                        "emailPortProvider must not be null"
                );

        this.jsonMapper =
                Objects.requireNonNull(
                        jsonMapper,
                        "jsonMapper must not be null"
                );
    }

    public int runOnce(
            int batchSize
    ) {

        /*
         * Graph is disabled by default.
         *
         * Therefore application startup must NOT fail merely
         * because no EmailNotificationPort exists.
         */
        EmailNotificationPort emailPort =
                emailPortProvider
                        .orderedStream()
                        .findFirst()
                        .orElse(null);

        if (emailPort == null) {
            return 0;
        }

        List<ClaimedNotification> claims =
                claimService.claimReady(
                        batchSize
                );

        for (ClaimedNotification claim : claims) {

            process(
                    claim,
                    emailPort
            );
        }

        return claims.size();
    }

    private void process(
            ClaimedNotification claim,
            EmailNotificationPort emailPort
    ) {

        if (claim.channel()
                != NotificationChannel.EMAIL) {

            claimService.markDeadLetter(
                    claim.id(),
                    "Unsupported notification channel: "
                            + claim.channel()
            );

            return;
        }

        if (!"TRANSACTION_COMPLETED".equals(
                claim.eventType()
        )) {

            claimService.markDeadLetter(
                    claim.id(),
                    "Unsupported notification event type: "
                            + claim.eventType()
            );

            return;
        }

        TransactionNotificationPayload payload;

        try {

            payload =
                    jsonMapper.readValue(
                            claim.payloadJson(),
                            TransactionNotificationPayload.class
                    );

        } catch (JacksonException exception) {

            /*
             * Invalid persisted JSON will never become valid
             * by retrying.
             */
            claimService.markDeadLetter(
                    claim.id(),
                    "Invalid notification payload: "
                            + exception.getMessage()
            );

            return;
        }

        try {

            emailPort.sendHtml(
                    claim.recipient(),
                    subject(payload),
                    html(payload)
            );

            claimService.markDelivered(
                    claim.id()
            );

        } catch (EmailDeliveryException exception) {

            handleDeliveryFailure(
                    claim,
                    exception
            );

        } catch (RuntimeException exception) {

            /*
             * Defensive handling for an adapter that violates
             * the EmailNotificationPort contract.
             *
             * Treat unknown infrastructure failures as
             * retryable, but still respect the retry budget.
             */
            if (claim.attemptNumber() < MAX_ATTEMPTS) {

                claimService.scheduleRetry(
                        claim.id(),
                        DEFAULT_RETRY_DELAY,
                        exceptionMessage(
                                exception
                        )
                );

            } else {

                claimService.markDeadLetter(
                        claim.id(),
                        exceptionMessage(
                                exception
                        )
                );
            }
        }
    }

    private void handleDeliveryFailure(
            ClaimedNotification claim,
            EmailDeliveryException exception
    ) {

        if (!exception.isRetryable()
                || claim.attemptNumber() >= MAX_ATTEMPTS) {

            claimService.markDeadLetter(
                    claim.id(),
                    exceptionMessage(
                            exception
                    )
            );

            return;
        }

        Duration delay =
                exception.getRetryAfter();

        if (delay == null
                || delay.isNegative()) {

            delay =
                    DEFAULT_RETRY_DELAY;
        }

        claimService.scheduleRetry(
                claim.id(),
                delay,
                exceptionMessage(
                        exception
                )
        );
    }

    private String subject(
            TransactionNotificationPayload payload
    ) {

        return switch (payload.type()) {

            case INCOMING_PAYMENT ->
                    "Money received";

            case TRANSFER ->
                    payload.direction()
                            == TransactionNotificationDirection.CREDIT
                            ? "Transfer received"
                            : "Transfer sent";

            case PAYOUT ->
                    "Payout wallet debit recorded";

            case FEE ->
                    "Fee charged";
        };
    }

    private String html(
            TransactionNotificationPayload payload
    ) {

        String amount =
                formattedAmount(
                        payload
                );

        String description =
                switch (payload.type()) {

                    case INCOMING_PAYMENT ->
                            "You received " + amount + ".";

                    case TRANSFER ->
                            payload.direction()
                                    == TransactionNotificationDirection.CREDIT
                                    ? "You received a transfer of "
                                      + amount
                                      + "."
                                    : "You sent a transfer of "
                                      + amount
                                      + ".";

                    case PAYOUT ->
                            "A debit of "
                                    + amount
                                    + " has been recorded against your wallet "
                                    + "for a payout instruction. "
                                    + "This confirms the ledger entry only; "
                                    + "it does not confirm that funds have "
                                    + "reached the destination bank account.";

                    case FEE ->
                            "A fee of "
                                    + amount
                                    + " was charged.";
                };

        return """
                <html>
                  <body>
                    <h2>Neos</h2>
                    <p>%s</p>
                    <p>
                      Reference: <strong>%s</strong>
                    </p>
                    <p>
                      Transaction ID: %d
                    </p>
                  </body>
                </html>
                """
                .formatted(
                        description,
                        escapeHtml(
                                payload.reference()
                        ),
                        payload.transactionId()
                );
    }

    private String formattedAmount(
            TransactionNotificationPayload payload
    ) {

        String asset =
                payload.asset();

        int separator =
                asset.lastIndexOf('/');

        int scale =
                separator >= 0
                        ? Integer.parseInt(
                        asset.substring(
                                separator + 1
                        )
                )
                        : 2;

        BigDecimal amount =
                BigDecimal.valueOf(
                        payload.amountMinor(),
                        scale
                );

        return payload.currency()
                + " "
                + amount.toPlainString();
    }

    private static String escapeHtml(
            String value
    ) {

        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String exceptionMessage(
            Throwable exception
    ) {

        String message =
                exception.getMessage();

        if (message == null
                || message.isBlank()) {

            return exception
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }
}