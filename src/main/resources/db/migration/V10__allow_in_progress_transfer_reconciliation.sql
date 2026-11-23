
-- V10: Allow an audited transition from a stale
-- IN_PROGRESS claim to SUCCEEDED, but only through
-- the controlled reconciliation service.
--
-- Existing UNKNOWN reconciliation remains supported.

ALTER TABLE customer_transfer_reconciliation_audit
DROP CONSTRAINT
        customer_transfer_reconciliation_audit_previous_status_check;

ALTER TABLE customer_transfer_reconciliation_audit
    ADD CONSTRAINT chk_reconciliation_audit_previous_status
        CHECK (
            previous_status IN (
                                'UNKNOWN',
                                'IN_PROGRESS'
                )
            );
