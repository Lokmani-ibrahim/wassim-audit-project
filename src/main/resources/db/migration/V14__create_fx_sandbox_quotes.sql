CREATE TABLE fx_sandbox_quotes (
                                   quote_id UUID PRIMARY KEY,
                                   user_id UUID NOT NULL,
                                   idempotency_key UUID NOT NULL,

                                   source_currency VARCHAR(3) NOT NULL,
                                   destination_currency VARCHAR(3) NOT NULL,

                                   source_minor NUMERIC(38, 0) NOT NULL,
                                   destination_minor NUMERIC(38, 0) NOT NULL,

                                   source_scale SMALLINT NOT NULL,
                                   destination_scale SMALLINT NOT NULL,

                                   exchange_rate NUMERIC(38, 18) NOT NULL,

                                   pricing_mode VARCHAR(32) NOT NULL
                                                               DEFAULT 'SANDBOX_INDICATIVE',

                                   executable BOOLEAN NOT NULL DEFAULT FALSE,

                                   created_at TIMESTAMPTZ NOT NULL,
                                   expires_at TIMESTAMPTZ NOT NULL,

                                   CONSTRAINT uq_fx_sandbox_customer_request
                                       UNIQUE (user_id, idempotency_key),

                                   CONSTRAINT ck_fx_sandbox_different_currencies
                                       CHECK (source_currency <> destination_currency),

                                   CONSTRAINT ck_fx_sandbox_source_positive
                                       CHECK (source_minor > 0),

                                   CONSTRAINT ck_fx_sandbox_destination_positive
                                       CHECK (destination_minor > 0),

                                   CONSTRAINT ck_fx_sandbox_rate_positive
                                       CHECK (exchange_rate > 0),

                                   CONSTRAINT ck_fx_sandbox_source_scale
                                       CHECK (source_scale BETWEEN 0 AND 6),

                                   CONSTRAINT ck_fx_sandbox_destination_scale
                                       CHECK (destination_scale BETWEEN 0 AND 6),

                                   CONSTRAINT ck_fx_sandbox_mode
                                       CHECK (pricing_mode = 'SANDBOX_INDICATIVE'),

                                   CONSTRAINT ck_fx_sandbox_not_executable
                                       CHECK (executable = FALSE),

                                   CONSTRAINT ck_fx_sandbox_expiry
                                       CHECK (expires_at > created_at)
);

CREATE INDEX idx_fx_sandbox_customer_created
    ON fx_sandbox_quotes (user_id, created_at DESC);