-- Keyset scans ordered by the users primary key, optionally narrowed by account status.
CREATE INDEX idx_users_account_status_id ON users (account_status, id);

-- Exact, case-insensitive email search uses lower(email) equality.
CREATE INDEX idx_users_email_lower ON users (lower(email));
