ALTER TABLE device
    ADD COLUMN IF NOT EXISTS hpke_public_key BYTEA NULL;

ALTER TABLE device
    DROP CONSTRAINT IF EXISTS device_hpke_public_key_length;

ALTER TABLE device
    ADD CONSTRAINT device_hpke_public_key_length
    CHECK (hpke_public_key IS NULL OR octet_length(hpke_public_key) = 32);

-- Pre-final pairing-created devices can be backfilled from their approved enrollment request.
-- Initial-bootstrap/recovery-created development rows had no durable HPKE identity and remain
-- NULL; the authenticated directory fails closed until those devices are reset/re-enrolled.
UPDATE device d
SET hpke_public_key = r.hpke_public_key
FROM device_enrollment_request r
WHERE d.hpke_public_key IS NULL
  AND d.device_id = r.target_device_id
  AND d.account_id = r.account_id
  AND r.approved_at IS NOT NULL
  AND octet_length(r.hpke_public_key) = 32;
