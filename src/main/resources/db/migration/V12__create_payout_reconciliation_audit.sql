
CREATE TABLE payout_reconciliation_audit (

                                             audit_id UUID PRIMARY KEY,

                                             payout_id UUID NOT NULL
                                                 REFERENCES payout_requests(payout_id),

                                             action VARCHAR(16) NOT NULL
                                                 CHECK (action IN ('RESERVE', 'SETTLE', 'RELEASE')),

    previous_status VARCHAR(32) NOT NULL,
    resulting_status VARCHAR(32) NOT NULL,

    formance_transaction_id BIGINT NOT NULL
        CHECK (formance_transaction_id >= 0),

    formance_reference TEXT NOT NULL
        CHECK (LENGTH(BTRIM(formance_reference)) > 0),

    actor_subject VARCHAR(255) NOT NULL
        CHECK (LENGTH(BTRIM(actor_subject)) > 0),

    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT payout_reconciliation_transition_check
    CHECK (
        (
            action = 'RESERVE'
            AND previous_status IN (
                'RESERVE_UNKNOWN',
                'RESERVE_IN_PROGRESS'
            )
            AND resulting_status = 'RESERVED'
        )
        OR
        (
            action = 'SETTLE'
            AND previous_status IN (
                'SETTLE_UNKNOWN',
                'SETTLE_IN_PROGRESS'
            )
            AND resulting_status = 'LEDGER_SETTLED'
        )
        OR
        (
            action = 'RELEASE'
            AND previous_status IN (
                'RELEASE_UNKNOWN',
                'RELEASE_IN_PROGRESS'
            )
            AND resulting_status = 'RELEASED'
        )
    ),

    CONSTRAINT payout_reconciliation_once_per_action
        UNIQUE (payout_id, action)
);

CREATE INDEX payout_reconciliation_audit_recent_idx
    ON payout_reconciliation_audit(recorded_at DESC);
