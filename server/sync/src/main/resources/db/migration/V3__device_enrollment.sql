CREATE TABLE IF NOT EXISTS device_enrollment_request (
    request_id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES account(account_id),
    target_device_id TEXT NOT NULL,
    hpke_public_key BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    approved_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS device_key_package (
    request_id TEXT PRIMARY KEY REFERENCES device_enrollment_request(request_id),
    package_bytes BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
