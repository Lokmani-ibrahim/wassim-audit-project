
-- Persist the reason for account suspension.
--
-- Existing suspended accounts are conservatively classified
-- as UNSPECIFIED and cannot be automatically reactivated.

ALTER TABLE users
    ADD COLUMN suspension_reason VARCHAR(32);

UPDATE users
SET suspension_reason = 'UNSPECIFIED'
WHERE account_status = 'SUSPENDED';

ALTER TABLE users
    ADD CONSTRAINT chk_users_suspension_reason
        CHECK (
            (
                account_status = 'SUSPENDED'
                    AND suspension_reason IS NOT NULL
                    AND suspension_reason IN (
                                              'KYC_REVERIFICATION',
                                              'KYC_FINAL_REJECTION',
                                              'ADMINISTRATIVE',
                                              'UNSPECIFIED'
                    )
                )
                OR
            (
                account_status <> 'SUSPENDED'
                    AND suspension_reason IS NULL
                )
            );
