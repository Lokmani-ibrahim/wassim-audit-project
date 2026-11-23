
CREATE TABLE payout_requests (

                                 payout_id UUID PRIMARY KEY,

                                 user_id UUID NOT NULL REFERENCES users(id),
                                 request_key UUID NOT NULL,

    -- Shared by reserve / settle / release.
    -- Formance prefixes the reference by operation type.
                                 operation_id VARCHAR(128) NOT NULL UNIQUE,

                                 currency VARCHAR(16) NOT NULL,
                                 amount_minor BIGINT NOT NULL
                                     CHECK (amount_minor > 0),

    -- Internal bank-master identifier, NOT an
    -- external beneficiary account.
                                 settlement_bank_id VARCHAR(128),

                                 status VARCHAR(32) NOT NULL
                                     CHECK (status IN (
                                                       'REQUESTED',
                                                       'RESERVE_IN_PROGRESS',
                                                       'RESERVE_UNKNOWN',
                                                       'RESERVED',
                                                       'SETTLE_IN_PROGRESS',
                                                       'SETTLE_UNKNOWN',
                                                       'RELEASE_IN_PROGRESS',
                                                       'RELEASE_UNKNOWN',
                                                       'LEDGER_SETTLED',
                                                       'RELEASED'
                                         )),

                                 reserve_ledger_transaction_id BIGINT,
                                 reserve_ledger_reference TEXT,

                                 settle_ledger_transaction_id BIGINT,
                                 settle_ledger_reference TEXT,

                                 release_ledger_transaction_id BIGINT,
                                 release_ledger_reference TEXT,

                                 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                 updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                 CONSTRAINT payout_request_unique_key
                                     UNIQUE (user_id, request_key),

                                 CONSTRAINT payout_operation_id_format
                                     CHECK (operation_id ~ '^[A-Za-z0-9_-]+$'),

    CONSTRAINT payout_bank_id_format
        CHECK (
            settlement_bank_id IS NULL
            OR (
                settlement_bank_id <> ''
                AND settlement_bank_id =
                    BTRIM(settlement_bank_id)
            )
        ),

    CONSTRAINT payout_transaction_ids_positive
        CHECK (
            (reserve_ledger_transaction_id IS NULL
                OR reserve_ledger_transaction_id >= 0)
            AND
            (settle_ledger_transaction_id IS NULL
                OR settle_ledger_transaction_id >= 0)
            AND
            (release_ledger_transaction_id IS NULL
                OR release_ledger_transaction_id >= 0)
        ),

    CONSTRAINT payout_reference_identity_check
        CHECK (
            (
                reserve_ledger_reference IS NULL
                OR reserve_ledger_reference =
                    'payout_reserve:' || operation_id
            )
            AND
            (
                settle_ledger_reference IS NULL
                OR settle_ledger_reference =
                    'payout_settle:' || operation_id
            )
            AND
            (
                release_ledger_reference IS NULL
                OR release_ledger_reference =
                    'payout_release:' || operation_id
            )
        ),

    -- A settlement needs a known bank-master account.
    CONSTRAINT payout_settlement_bank_required
        CHECK (
            status NOT IN (
                'SETTLE_IN_PROGRESS',
                'SETTLE_UNKNOWN',
                'LEDGER_SETTLED'
            )
            OR settlement_bank_id IS NOT NULL
        ),

    -- Ledger evidence must match the lifecycle state.
    CONSTRAINT payout_ledger_state_check
        CHECK (

            (
                status IN (
                    'REQUESTED',
                    'RESERVE_IN_PROGRESS',
                    'RESERVE_UNKNOWN'
                )
                AND reserve_ledger_transaction_id IS NULL
                AND reserve_ledger_reference IS NULL
                AND settle_ledger_transaction_id IS NULL
                AND settle_ledger_reference IS NULL
                AND release_ledger_transaction_id IS NULL
                AND release_ledger_reference IS NULL
            )

            OR

            (
                status IN (
                    'RESERVED',
                    'SETTLE_IN_PROGRESS',
                    'SETTLE_UNKNOWN',
                    'RELEASE_IN_PROGRESS',
                    'RELEASE_UNKNOWN'
                )
                AND reserve_ledger_transaction_id IS NOT NULL
                AND reserve_ledger_reference IS NOT NULL
                AND settle_ledger_transaction_id IS NULL
                AND settle_ledger_reference IS NULL
                AND release_ledger_transaction_id IS NULL
                AND release_ledger_reference IS NULL
            )

            OR

            (
                status = 'LEDGER_SETTLED'
                AND reserve_ledger_transaction_id IS NOT NULL
                AND reserve_ledger_reference IS NOT NULL
                AND settle_ledger_transaction_id IS NOT NULL
                AND settle_ledger_reference IS NOT NULL
                AND release_ledger_transaction_id IS NULL
                AND release_ledger_reference IS NULL
            )

            OR

            (
                status = 'RELEASED'
                AND reserve_ledger_transaction_id IS NOT NULL
                AND reserve_ledger_reference IS NOT NULL
                AND settle_ledger_transaction_id IS NULL
                AND settle_ledger_reference IS NULL
                AND release_ledger_transaction_id IS NOT NULL
                AND release_ledger_reference IS NOT NULL
            )
        )
);

CREATE INDEX payout_requests_user_recent_idx
    ON payout_requests (user_id, created_at DESC);

CREATE INDEX payout_requests_unresolved_idx
    ON payout_requests (status, updated_at)
    WHERE status IN (
        'RESERVE_IN_PROGRESS',
        'RESERVE_UNKNOWN',
        'SETTLE_IN_PROGRESS',
        'SETTLE_UNKNOWN',
        'RELEASE_IN_PROGRESS',
        'RELEASE_UNKNOWN'
    );

CREATE FUNCTION enforce_payout_request_immutability()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN

    -- Financial request identity must never change.
    IF ROW(
        NEW.user_id,
        NEW.request_key,
        NEW.operation_id,
        NEW.currency,
        NEW.amount_minor
    ) IS DISTINCT FROM ROW(
        OLD.user_id,
        OLD.request_key,
        OLD.operation_id,
        OLD.currency,
        OLD.amount_minor
    ) THEN

        RAISE EXCEPTION
            'Payout financial identity is immutable'
            USING ERRCODE = '23514';

END IF;

    -- Once a bank-master account is assigned,
    -- it cannot be silently replaced.
    IF OLD.settlement_bank_id IS NOT NULL
       AND NEW.settlement_bank_id
           IS DISTINCT FROM OLD.settlement_bank_id
    THEN

        RAISE EXCEPTION
            'Payout settlement bank is immutable'
            USING ERRCODE = '23514';

END IF;

    -- A terminal ledger outcome cannot be reversed.
    IF OLD.status IN ('LEDGER_SETTLED', 'RELEASED')
       AND NEW.status IS DISTINCT FROM OLD.status
    THEN

        RAISE EXCEPTION
            'Terminal payout status is immutable'
            USING ERRCODE = '23514';

END IF;

    -- Confirmed Formance transaction evidence
    -- must never be removed or replaced.
    IF OLD.reserve_ledger_transaction_id IS NOT NULL
       AND ROW(
           NEW.reserve_ledger_transaction_id,
           NEW.reserve_ledger_reference
       ) IS DISTINCT FROM ROW(
           OLD.reserve_ledger_transaction_id,
           OLD.reserve_ledger_reference
       )
    THEN

        RAISE EXCEPTION
            'Reserve ledger evidence is immutable'
            USING ERRCODE = '23514';

END IF;

    IF OLD.settle_ledger_transaction_id IS NOT NULL
       AND ROW(
           NEW.settle_ledger_transaction_id,
           NEW.settle_ledger_reference
       ) IS DISTINCT FROM ROW(
           OLD.settle_ledger_transaction_id,
           OLD.settle_ledger_reference
       )
    THEN

        RAISE EXCEPTION
            'Settlement ledger evidence is immutable'
            USING ERRCODE = '23514';

END IF;

    IF OLD.release_ledger_transaction_id IS NOT NULL
       AND ROW(
           NEW.release_ledger_transaction_id,
           NEW.release_ledger_reference
       ) IS DISTINCT FROM ROW(
           OLD.release_ledger_transaction_id,
           OLD.release_ledger_reference
       )
    THEN

        RAISE EXCEPTION
            'Release ledger evidence is immutable'
            USING ERRCODE = '23514';

END IF;

RETURN NEW;

END;
$$;

CREATE TRIGGER payout_request_immutability_guard
    BEFORE UPDATE ON payout_requests
    FOR EACH ROW
    EXECUTE FUNCTION enforce_payout_request_immutability();
