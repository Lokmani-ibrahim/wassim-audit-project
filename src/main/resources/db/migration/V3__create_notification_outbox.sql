CREATE TABLE notification_outbox (
                                     id UUID PRIMARY KEY,

                                     notification_key VARCHAR(255) NOT NULL,
                                     event_type VARCHAR(64) NOT NULL,
                                     channel VARCHAR(32) NOT NULL,

                                     user_id UUID NOT NULL,
                                     recipient VARCHAR(320) NOT NULL,

                                     payload_json TEXT NOT NULL,

                                     status VARCHAR(32) NOT NULL,
                                     attempt_count INTEGER NOT NULL DEFAULT 0,

                                     next_attempt_at TIMESTAMPTZ NOT NULL,

                                     last_error TEXT,
                                     published_at TIMESTAMPTZ,

                                     created_at TIMESTAMPTZ NOT NULL,
                                     updated_at TIMESTAMPTZ NOT NULL,

                                     CONSTRAINT uq_notification_outbox_key
                                         UNIQUE (notification_key),

                                     CONSTRAINT ck_notification_outbox_attempt_count
                                         CHECK (attempt_count >= 0)
);

CREATE INDEX idx_notification_outbox_pending
    ON notification_outbox (
                            status,
                            next_attempt_at
        );

CREATE INDEX idx_notification_outbox_user
    ON notification_outbox (
                            user_id,
                            created_at DESC
        );