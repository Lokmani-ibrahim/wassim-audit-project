
package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class CustomerTransferReconciliationQueryService {

    private final JdbcTemplate jdbc;

    public CustomerTransferReconciliationQueryService(
            JdbcTemplate jdbc
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public enum UnresolvedStatus {
        IN_PROGRESS,
        UNKNOWN
    }

    public record UnresolvedTransfer(
            UUID senderUserId,
            UUID requestKey,
            UUID recipientUserId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId,
            UnresolvedStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /**
     * Read-only inventory of unresolved transfers.
     *
     * UNKNOWN records are always included.
     *
     * IN_PROGRESS records are included only when
     * updatedAt <= staleBefore.
     *
     * This method does not:
     * - contact Formance
     * - retry transactions
     * - update database state
     */
    @Transactional(readOnly = true)
    public List<UnresolvedTransfer> findForSender(
            UUID senderId,
            Instant staleBefore,
            int limit
    ) {

        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(staleBefore, "staleBefore");

        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 100"
            );
        }

        return jdbc.query(
                """
                SELECT
                    sender_user_id,
                    request_key,
                    recipient_user_id,
                    currency,
                    amount_minor,
                    operation_id,
                    status,
                    created_at,
                    updated_at
                FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND (
                      status = 'UNKNOWN'
                      OR (
                          status = 'IN_PROGRESS'
                          AND updated_at <= ?
                      )
                  )
                ORDER BY updated_at ASC, request_key ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new UnresolvedTransfer(
                        rs.getObject(
                                "sender_user_id",
                                UUID.class
                        ),
                        rs.getObject(
                                "request_key",
                                UUID.class
                        ),
                        rs.getObject(
                                "recipient_user_id",
                                UUID.class
                        ),
                        LedgerCurrency.valueOf(
                                rs.getString("currency")
                        ),
                        rs.getLong("amount_minor"),
                        rs.getString("operation_id"),
                        UnresolvedStatus.valueOf(
                                rs.getString("status")
                        ),
                        rs.getObject(
                                "created_at",
                                OffsetDateTime.class
                        ).toInstant(),
                        rs.getObject(
                                "updated_at",
                                OffsetDateTime.class
                        ).toInstant()
                ),
                senderId,
                java.sql.Timestamp.from(staleBefore),
                limit
        );
    }
}
