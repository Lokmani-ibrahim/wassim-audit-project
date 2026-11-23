
CREATE TABLE fx_demo_conversions (
                                     operation_id UUID PRIMARY KEY,
                                     user_id UUID NOT NULL,
                                     quote_id UUID NOT NULL,
                                     request_key UUID NOT NULL,

                                     bank_id VARCHAR(128) NOT NULL,

                                     source_currency VARCHAR(3) NOT NULL,
                                     destination_currency VARCHAR(3) NOT NULL,
                                     source_minor BIGINT NOT NULL,
                                     destination_minor BIGINT NOT NULL,
                                     exchange_rate NUMERIC(38, 18) NOT NULL,

                                     status VARCHAR(32) NOT NULL DEFAULT 'CLAIMED',

                                     reserve_reference VARCHAR(128) NOT NULL,
                                     reserve_transaction_id BIGINT,

                                     settlement_reference VARCHAR(128) NOT NULL,
                                     settlement_transaction_id BIGINT,

                                     simulated BOOLEAN NOT NULL DEFAULT TRUE,

                                     created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                     updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                     CONSTRAINT uq_fx_demo_request
                                         UNIQUE (user_id, request_key),

                                     CONSTRAINT uq_fx_demo_quote
                                         UNIQUE (quote_id),

                                     CONSTRAINT uq_fx_demo_reserve_reference
                                         UNIQUE (reserve_reference),

                                     CONSTRAINT uq_fx_demo_settlement_reference
                                         UNIQUE (settlement_reference),

                                     CONSTRAINT ck_fx_demo_amounts
                                         CHECK (source_minor > 0 AND destination_minor > 0),

                                     CONSTRAINT ck_fx_demo_currencies
                                         CHECK (source_currency <> destination_currency),

                                     CONSTRAINT ck_fx_demo_rate
                                         CHECK (exchange_rate > 0),

                                     CONSTRAINT ck_fx_demo_simulated
                                         CHECK (simulated = TRUE),

                                     CONSTRAINT ck_fx_demo_transaction_ids
                                         CHECK (
                                             (reserve_transaction_id IS NULL
                                                 OR reserve_transaction_id >= 0)
                                                 AND
                                             (settlement_transaction_id IS NULL
                                                 OR settlement_transaction_id >= 0)
                                             ),

                                     CONSTRAINT ck_fx_demo_status
                                         CHECK (
                                             status IN (
                                                        'CLAIMED',
                                                        'RESERVE_POSTING',
                                                        'RESERVE_UNKNOWN',
                                                        'RESERVED',
                                                        'SETTLE_POSTING',
                                                        'SETTLE_UNKNOWN',
                                                        'SUCCEEDED'
                                                 )
                                             ),

                                     CONSTRAINT ck_fx_demo_success
                                         CHECK (
                                             status <> 'SUCCEEDED'
                                                 OR (
                                                 reserve_transaction_id IS NOT NULL
                                                     AND settlement_transaction_id IS NOT NULL
                                                 )
                                             )
);

CREATE INDEX idx_fx_demo_customer_created
    ON fx_demo_conversions (user_id, created_at DESC);

CREATE INDEX idx_fx_demo_unresolved
    ON fx_demo_conversions (status, updated_at)
    WHERE status IN (
        'RESERVE_POSTING',
        'RESERVE_UNKNOWN',
        'SETTLE_POSTING',
        'SETTLE_UNKNOWN'
    );
