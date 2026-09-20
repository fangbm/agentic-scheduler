CREATE TABLE IF NOT EXISTS recovery_envelope (
    account_id TEXT PRIMARY KEY REFERENCES account(account_id),
    envelope_bytes BYTEA NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
