
package com.fintech.ledger;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@Rollback
class PayoutRequestSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    private UUID payoutId;
    private UUID userId;
    private UUID requestKey;
    private String operationId;

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        UserEntity user = users.saveAndFlush(
                new UserEntity(
                        "payout-schema-" + unique,
                        "payout-schema-" + unique + "@example.com",
                        null
                )
        );

        userId = user.getId();
        payoutId = UUID.randomUUID();
        requestKey = UUID.randomUUID();

        operationId = "po_" +
                payoutId.toString().replace("-", "");
    }

    private void insertRequested() {

        jdbc.update(
                """
                INSERT INTO payout_requests (
                    payout_id,
                    user_id,
                    request_key,
                    operation_id,
                    currency,
                    amount_minor,
                    status
                )
                VALUES (?, ?, ?, ?, 'EUR', 5000, 'REQUESTED')
                """,
                payoutId,
                userId,
                requestKey,
                operationId
        );
    }

    @Test
    void payoutCanAdvanceToReservedWithEvidence() {

        insertRequested();

        jdbc.update(
                """
                UPDATE payout_requests
                SET status = 'RESERVE_IN_PROGRESS',
                    updated_at = NOW()
                WHERE payout_id = ?
                """,
                payoutId
        );

        jdbc.update(
                """
                UPDATE payout_requests
                SET status = 'RESERVED',
                    reserve_ledger_transaction_id = 101,
                    reserve_ledger_reference = ?,
                    updated_at = NOW()
                WHERE payout_id = ?
                """,
                "payout_reserve:" + operationId,
                payoutId
        );

        String status = jdbc.queryForObject(
                """
                SELECT status
                FROM payout_requests
                WHERE payout_id = ?
                """,
                String.class,
                payoutId
        );

        assertEquals("RESERVED", status);
    }

    @Test
    void sameCustomerCannotReuseRequestKey() {

        insertRequested();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        INSERT INTO payout_requests (
                            payout_id,
                            user_id,
                            request_key,
                            operation_id,
                            currency,
                            amount_minor,
                            status
                        )
                        VALUES (?, ?, ?, ?, 'EUR', 5000, 'REQUESTED')
                        """,
                        UUID.randomUUID(),
                        userId,
                        requestKey,
                        "po_" + UUID.randomUUID()
                                .toString().replace("-", "")
                )
        );
    }

    @Test
    void amountCannotChangeAfterCreation() {

        insertRequested();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        UPDATE payout_requests
                        SET amount_minor = 9000
                        WHERE payout_id = ?
                        """,
                        payoutId
                )
        );
    }

    @Test
    void settlementRequiresConfirmedReservation() {

        insertRequested();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        UPDATE payout_requests
                        SET status = 'LEDGER_SETTLED',
                            settlement_bank_id = 'sponsor'
                        WHERE payout_id = ?
                        """,
                        payoutId
                )
        );
    }

    @Test
    void settledPayoutCannotBeReleased() {

        insertRequested();

        jdbc.update(
                """
                UPDATE payout_requests
                SET status = 'RESERVED',
                    reserve_ledger_transaction_id = 101,
                    reserve_ledger_reference = ?
                WHERE payout_id = ?
                """,
                "payout_reserve:" + operationId,
                payoutId
        );

        jdbc.update(
                """
                UPDATE payout_requests
                SET status = 'LEDGER_SETTLED',
                    settlement_bank_id = 'sponsor',
                    settle_ledger_transaction_id = 102,
                    settle_ledger_reference = ?
                WHERE payout_id = ?
                """,
                "payout_settle:" + operationId,
                payoutId
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        UPDATE payout_requests
                        SET status = 'RELEASED',
                            settle_ledger_transaction_id = NULL,
                            settle_ledger_reference = NULL,
                            release_ledger_transaction_id = 103,
                            release_ledger_reference = ?
                        WHERE payout_id = ?
                        """,
                        "payout_release:" + operationId,
                        payoutId
                )
        );
    }
}
