-- BUG-FX-2: a conversion the ledger refuses outright (insufficient funds at
-- reservation) is a definitive REJECTED outcome, not an unresolved one.
-- Nothing was reserved, so the quote stays usable by another request.

ALTER TABLE fx_demo_conversions
    DROP CONSTRAINT ck_fx_demo_status;

ALTER TABLE fx_demo_conversions
    ADD CONSTRAINT ck_fx_demo_status
        CHECK (
            status IN (
                       'CLAIMED',
                       'RESERVE_POSTING',
                       'RESERVE_UNKNOWN',
                       'RESERVED',
                       'SETTLE_POSTING',
                       'SETTLE_UNKNOWN',
                       'SUCCEEDED',
                       'REJECTED'
                )
            );

-- A REJECTED operation must not have ledger evidence.
ALTER TABLE fx_demo_conversions
    ADD CONSTRAINT ck_fx_demo_rejected_without_ledger
        CHECK (
            status <> 'REJECTED'
                OR (
                reserve_transaction_id IS NULL
                    AND settlement_transaction_id IS NULL
                )
            );

-- One live (non-rejected) operation per quote.
ALTER TABLE fx_demo_conversions
    DROP CONSTRAINT uq_fx_demo_quote;

CREATE UNIQUE INDEX uq_fx_demo_quote_not_rejected
    ON fx_demo_conversions (quote_id)
    WHERE status <> 'REJECTED';
