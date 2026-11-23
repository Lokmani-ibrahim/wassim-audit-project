-- Add support for temporary Sumsub rejection
-- and durable provider-event ordering metadata.

ALTER TABLE kyc_status
DROP CONSTRAINT chk_kyc_status;

ALTER TABLE kyc_status
    ADD CONSTRAINT chk_kyc_status
        CHECK (
            status IN (
                       'NOT_STARTED',
                       'PENDING',
                       'IN_REVIEW',
                       'APPROVED',
                       'REJECTED',
                       'RESUBMISSION_REQUIRED'
                )
            );

-- Nullable to preserve existing A3/C2/C4 records.
ALTER TABLE kyc_status
    ADD COLUMN last_provider_event_at TIMESTAMPTZ;