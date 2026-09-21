CREATE TABLE IF NOT EXISTS account (
    account_id TEXT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS device (
    device_id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES account(account_id),
    credential_hash BYTEA NOT NULL UNIQUE,
    revoked_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sync_space (
    sync_space_id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES account(account_id),
    next_cursor BIGINT NOT NULL DEFAULT 0 CHECK (next_cursor >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sync_space_membership (
    sync_space_id TEXT NOT NULL REFERENCES sync_space(sync_space_id),
    device_id TEXT NOT NULL REFERENCES device(device_id),
    PRIMARY KEY (sync_space_id, device_id)
);

CREATE TABLE IF NOT EXISTS encrypted_operation_envelope (
    sync_space_id TEXT NOT NULL REFERENCES sync_space(sync_space_id),
    server_cursor BIGINT NOT NULL CHECK (server_cursor > 0),
    mutation_id TEXT NOT NULL,
    sender_device_id TEXT NOT NULL REFERENCES device(device_id),
    key_epoch BIGINT NOT NULL CHECK (key_epoch >= 0),
    ciphertext BYTEA NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (sync_space_id, mutation_id),
    UNIQUE (sync_space_id, server_cursor)
);

CREATE INDEX IF NOT EXISTS encrypted_operation_envelope_cursor_idx
    ON encrypted_operation_envelope(sync_space_id, server_cursor);
