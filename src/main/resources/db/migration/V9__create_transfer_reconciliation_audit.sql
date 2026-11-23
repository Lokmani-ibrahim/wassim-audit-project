CREATE TABLE customer_transfer_reconciliation_audit (
                                                        audit_id UUID PRIMARY KEY,

                                                        sender_user_id UUID NOT NULL,
                                                        request_key UUID NOT NULL,
                                                        operation_id VARCHAR(128) NOT NULL,

                                                        action VARCHAR(32) NOT NULL
                                                            CHECK (action = 'CONFIRM_POSTED'),

    previous_status VARCHAR(16) NOT NULL
        CHECK (previous_status = 'UNKNOWN'),

    resulting_status VARCHAR(16) NOT NULL
        CHECK (resulting_status = 'SUCCEEDED'),

    formance_transaction_id BIGINT NOT NULL
        CHECK (formance_transaction_id >= 0),

    actor_subject VARCHAR(255) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (sender_user_id, request_key)
);

CREATE INDEX transfer_reconciliation_audit_recorded_idx
    ON customer_transfer_reconciliation_audit (recorded_at);