-- ============================================================
-- NEOS
-- Flyway Migration V2
-- Terms and Conditions Acceptance
-- ============================================================

CREATE TABLE terms_acceptances (
                                   id UUID PRIMARY KEY,

                                   user_id UUID NOT NULL,

                                   terms_version VARCHAR(50) NOT NULL,

                                   accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                   CONSTRAINT fk_terms_acceptances_user
                                       FOREIGN KEY (user_id)
                                           REFERENCES users(id),

                                   CONSTRAINT uq_terms_acceptances_user_version
                                       UNIQUE (user_id, terms_version)
);

CREATE INDEX idx_terms_acceptances_user_id
    ON terms_acceptances(user_id);