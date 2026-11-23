CREATE TABLE fx_conversion_operations (

                                          operation_id UUID PRIMARY KEY,

                                          user_id UUID NOT NULL,
                                          idempotency_key UUID NOT NULL,

                                          provider VARCHAR(64) NOT NULL,
                                          provider_quote_id VARCHAR(128) NOT NULL,

                                          source_currency VARCHAR(3) NOT NULL,
                                          destination_currency VARCHAR(3) NOT NULL,

                                          source_minor BIGINT NOT NULL,
                                          destination_minor BIGINT NOT NULL,

                                          exchange_rate NUMERIC(38, 18) NOT NULL,
                                          quote_expires_at TIMESTAMPTZ NOT NULL,

                                          ledger_reference VARCHAR(128) NOT NULL,
                                          ledger_transaction_id BIGINT,

                                          status VARCHAR(24) NOT NULL DEFAULT 'CLAIMED',

                                          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                          CONSTRAINT uq_fx_conversion_request
                                              UNIQUE (user_id, idempotency_key),

                                          CONSTRAINT uq_fx_conversion_provider_quote
                                              UNIQUE (provider, provider_quote_id),

                                          CONSTRAINT uq_fx_conversion_ledger_reference
                                              UNIQUE (ledger_reference),

                                          CONSTRAINT ck_fx_conversion_distinct_currencies
                                              CHECK (source_currency <> destination_currency),

                                          CONSTRAINT ck_fx_conversion_positive_amounts
                                              CHECK (source_minor > 0 AND destination_minor > 0),

                                          CONSTRAINT ck_fx_conversion_positive_rate
                                              CHECK (exchange_rate > 0),

                                          CONSTRAINT ck_fx_conversion_status
                                              CHECK (status IN (
                                                                'CLAIMED',
                                                                'POSTING',
                                                                'UNKNOWN',
                                                                'SUCCEEDED'
                                                  )),

                                          CONSTRAINT ck_fx_conversion_success_has_evidence
                                              CHECK (
                                                  status <> 'SUCCEEDED'
                                                      OR ledger_transaction_id IS NOT NULL
                                                  ),

                                          CONSTRAINT ck_fx_conversion_transaction_id_positive
                                              CHECK (
                                                  ledger_transaction_id IS NULL
                                                      OR ledger_transaction_id > 0
                                                  ),

                                          CONSTRAINT ck_fx_conversion_not_sandbox
                                              CHECK (
                                                  UPPER(provider) NOT LIKE '%SANDBOX%'
                                                      AND UPPER(provider) NOT LIKE '%MOCK%'
                                                      AND UPPER(provider) NOT LIKE '%TEST%'
                                                  )
);

CREATE INDEX idx_fx_conversion_user_created
    ON fx_conversion_operations (
                                 user_id,
                                 created_at DESC
        );

CREATE INDEX idx_fx_conversion_reconciliation
    ON fx_conversion_operations (
                                 status,
                                 updated_at
        )
    WHERE status IN ('POSTING', 'UNKNOWN');