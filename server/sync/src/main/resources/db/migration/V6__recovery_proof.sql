CREATE TABLE IF NOT EXISTS recovery_proof (
    account_id TEXT PRIMARY KEY REFERENCES account(account_id),
    proof_hash BYTEA NOT NULL CHECK (octet_length(proof_hash) = 32),
    counter BIGINT NOT NULL CHECK (counter >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS recovery_enrollment_request (
    request_id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES account(account_id),
    target_device_id TEXT NOT NULL REFERENCES device(device_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
