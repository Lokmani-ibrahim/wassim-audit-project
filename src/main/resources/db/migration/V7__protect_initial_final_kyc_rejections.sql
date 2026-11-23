ALTER TABLE kyc_status
    ADD COLUMN ever_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN initial_final_rejection_locked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN manual_review_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN manual_review_trigger VARCHAR(32),
    ADD COLUMN manual_review_trigger_at TIMESTAMPTZ;

-- Preserve approval history for currently approved customers.
UPDATE kyc_status
SET ever_approved = TRUE
WHERE status = 'APPROVED';