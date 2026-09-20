CREATE TABLE IF NOT EXISTS account_invitation (
    invitation_hash BYTEA PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES account(account_id),
    sync_space_id TEXT NOT NULL REFERENCES sync_space(sync_space_id),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS account_invitation_active_idx
    ON account_invitation(account_id, expires_at)
    WHERE consumed_at IS NULL;
