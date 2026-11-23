UPDATE notification_outbox
SET status = 'PROVIDER_ACCEPTED',
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'DELIVERED';