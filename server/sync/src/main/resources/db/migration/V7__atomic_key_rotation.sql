CREATE TABLE IF NOT EXISTS sync_key_rotation (
    rotation_id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES account(account_id),
    revoked_device_id TEXT NOT NULL REFERENCES device(device_id),
    request_hash BYTEA NOT NULL CHECK (octet_length(request_hash) = 32),
    recovery_envelope BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sync_key_rotation_package (
    rotation_id TEXT NOT NULL REFERENCES sync_key_rotation(rotation_id),
    device_id TEXT NOT NULL REFERENCES device(device_id),
    package_bytes BYTEA NOT NULL,
    PRIMARY KEY (rotation_id, device_id)
);
