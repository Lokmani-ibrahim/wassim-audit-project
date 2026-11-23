package com.fintech.notifications;

import com.fintech.identity.IdentityLookupService;
import com.fintech.notifications.entity.NotificationOutboxEntity;
import com.fintech.notifications.repository.NotificationFeedRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only in-app notification feed (Option A).
 *
 * Reads the authenticated customer's existing
 * notification_outbox rows. It never changes them and has
 * no effect on email delivery.
 *
 * Rows are listed whatever their delivery status
 * (PENDING, PROVIDER_ACCEPTED, DEAD_LETTER, ...): the money
 * movement happened even when the email did not go out.
 */
@Service
public class NotificationFeedService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    NotificationFeedService.class
            );

    /*
     * The only event type written to the outbox today
     * (NotificationOutboxService). Other event types are
     * not part of the feed until they are mapped here.
     */
    static final String TRANSACTION_COMPLETED =
            "TRANSACTION_COMPLETED";

    public static final int DEFAULT_PAGE_SIZE =
            20;

    public static final int MAX_PAGE_SIZE =
            100;

    private final IdentityLookupService identityLookupService;

    private final NotificationFeedRepository feedRepository;

    private final JsonMapper jsonMapper;

    public NotificationFeedService(
            IdentityLookupService identityLookupService,
            NotificationFeedRepository feedRepository,
            JsonMapper jsonMapper
    ) {

        this.identityLookupService =
                Objects.requireNonNull(
                        identityLookupService,
                        "identityLookupService must not be null"
                );

        this.feedRepository =
                Objects.requireNonNull(
                        feedRepository,
                        "feedRepository must not be null"
                );

        this.jsonMapper =
                Objects.requireNonNull(
                        jsonMapper,
                        "jsonMapper must not be null"
                );
    }

    @Transactional(readOnly = true)
    public NotificationFeedPage getFeed(
            String keycloakSubject,
            int pageSize,
            String cursor
    ) {

        if (pageSize < 1
                || pageSize > MAX_PAGE_SIZE) {

            throw new InvalidNotificationFeedRequestException(
                    "pageSize must be between 1 and "
                            + MAX_PAGE_SIZE
            );
        }

        NotificationFeedCursor after =
                cursor == null
                        ? null
                        : NotificationFeedCursor.decode(
                        cursor
                );

        UUID userId =
                identityLookupService
                        .findUserIdByKeycloakSubject(
                                keycloakSubject
                        )
                        .orElseThrow(
                                NotificationFeedCustomerNotFoundException::new
                        );

        /*
         * One extra row tells whether another page exists.
         */
        Limit limit =
                Limit.of(
                        pageSize + 1
                );

        List<NotificationOutboxEntity> rows =
                after == null
                        ? feedRepository.findFirstPage(
                        userId,
                        TRANSACTION_COMPLETED,
                        limit
                )
                        : feedRepository.findPageAfter(
                        userId,
                        TRANSACTION_COMPLETED,
                        after.createdAt(),
                        after.id(),
                        limit
                );

        boolean hasMore =
                rows.size() > pageSize;

        List<NotificationOutboxEntity> page =
                hasMore
                        ? rows.subList(
                        0,
                        pageSize
                )
                        : rows;

        List<NotificationFeedItem> items =
                new ArrayList<>(
                        page.size()
                );

        for (NotificationOutboxEntity row : page) {
            toItem(
                    row,
                    userId
            ).ifPresent(
                    items::add
            );
        }

        /*
         * The cursor follows the last ROW read, not the last
         * item kept, so a skipped row never stalls paging.
         */
        String nextCursor =
                hasMore
                        ? new NotificationFeedCursor(
                        page.getLast().getCreatedAt(),
                        page.getLast().getId()
                ).encode()
                        : null;

        return new NotificationFeedPage(
                List.copyOf(
                        items
                ),
                hasMore,
                nextCursor
        );
    }

    private Optional<NotificationFeedItem> toItem(
            NotificationOutboxEntity row,
            UUID userId
    ) {

        TransactionNotificationPayload payload;

        try {

            payload =
                    jsonMapper.readValue(
                            row.getPayloadJson(),
                            TransactionNotificationPayload.class
                    );

        } catch (RuntimeException exception) {

            /*
             * Log the row id only: the payload holds
             * customer financial data.
             */
            log.warn(
                    "Skipping notification feed row with unreadable payload id={}",
                    row.getId()
            );

            return Optional.empty();
        }

        if (payload == null
                || !userId.equals(payload.userId())) {

            log.warn(
                    "Skipping notification feed row with inconsistent owner id={}",
                    row.getId()
            );

            return Optional.empty();
        }

        return Optional.of(
                new NotificationFeedItem(
                        row.getId(),
                        payload.type(),
                        payload.direction(),
                        payload.currency(),
                        payload.amountMinor(),
                        payload.transactionId(),
                        payload.reference(),
                        isTestCredit(
                                payload.type()
                        ),
                        row.getCreatedAt()
                )
        );
    }

    /*
     * KNOWN LIMITATION (EMAIL-2, request R6).
     *
     * Today the only producer of INCOMING_PAYMENT
     * notifications is the back-office SyntheticCreditService,
     * so every INCOMING_PAYMENT row is a test credit.
     *
     * NotificationFeedTestCreditArchitectureTest fails as soon
     * as another class produces INCOMING_PAYMENT. When real
     * incoming payments (bank top-up) exist this rule is
     * wrong, and the payload must carry an explicit marker
     * set by SyntheticCreditService.
     */
    static boolean isTestCredit(
            TransactionNotificationType type
    ) {
        return type == TransactionNotificationType.INCOMING_PAYMENT;
    }
}
