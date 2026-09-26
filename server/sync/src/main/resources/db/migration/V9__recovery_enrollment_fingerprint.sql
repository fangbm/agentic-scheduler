ALTER TABLE recovery_enrollment_request
    ADD COLUMN request_fingerprint BYTEA;

ALTER TABLE recovery_enrollment_request
    ADD CONSTRAINT recovery_enrollment_request_fingerprint_size
    CHECK (request_fingerprint IS NULL OR octet_length(request_fingerprint) = 32);
