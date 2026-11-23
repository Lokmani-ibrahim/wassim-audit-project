CREATE TABLE customer_transfer_idempotency (

                                               sender_user_id UUID NOT NULL,
                                               request_key UUID NOT NULL,

                                               recipient_user_id UUID NOT NULL,
                                               currency VARCHAR(16) NOT NULL,
                                               amount_minor BIGINT NOT NULL
                                                   CHECK (amount_minor > 0),

                                               operation_id VARCHAR(128) NOT NULL UNIQUE,

                                               status VARCHAR(16) NOT NULL
                                                   CHECK (status IN (
                                                                     'IN_PROGRESS',
                                                                     'SUCCEEDED',
                                                                     'UNKNOWN'
                                                       )),

                                               ledger_transaction_id BIGINT,
                                               ledger_reference TEXT,

                                               created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                               updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                               PRIMARY KEY (sender_user_id, request_key),

                                               CONSTRAINT customer_transfer_result_check CHECK (
                                                   (
                                                       status = 'SUCCEEDED'
                                                           AND ledger_transaction_id IS NOT NULL
                                                           AND ledger_reference IS NOT NULL
                                                       )
                                                       OR
                                                   (
                                                       status IN ('IN_PROGRESS', 'UNKNOWN')
                                                           AND ledger_transaction_id IS NULL
                                                           AND ledger_reference IS NULL
                                                       )
                                                   )
);