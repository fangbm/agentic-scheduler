# D9-02-04 — Agent history transport integration

Status: implementation / Draft PR; production V3 remains disabled by OD-012.

Authority: AGT-013; protocol draft §8; protocol freeze W1/W4/W5/L1;
completed D9-02-01/02/03 contracts. Baseline: feature/d9-02-agent-sync,
360119e (PR #22 merged).

## Scope and acceptance

- Separate business outbound failures/compatibility holds from authenticated
  inbound progression. Retain unauthorized V2 and its full business causal
  dependency closure; never skip/rewrite dots or mark held operations uploaded.
- Add an explicitly injected V3 transport/receive path, separate Agent outbox,
  per-space default-off user consent and durable exact ciphertext retry.
- Keep one frozen V3 event per unchanged D8 envelope, operation ID binding,
  AAD, AEAD, epochs and 262144-byte encoded plaintext cap.
- Preflight complete finalized turns and hold every member plus manifest until
  referenced D7 facts are durably shared; independent consented turns progress.
- Received audit only looks up local D7 facts. Consent-off receive keeps only
  quarantine/backfill metadata, with no plaintext Agent history projection.
- Preserve separate Agent frontier/backfill and D7 business causal state.
- Verify holds, release, consent, retries, restart, receive dispatch, unchanged
  D8 authentication and independently reported failures with targeted tests and
  the repository CI workflow.

## Existing interface audit

The v13 Agent outbox already persists immutable operations and READY/HELD/
UPLOADED states, but has no ciphertext or conversation-consent storage/port.
D8 ciphertext is attached to a business journal record, so it cannot hold an
Agent operation without manufacturing a D7 mutation. This slice needs isolated
transport persistence, following the explicit SQL extension/migration pattern.
Consent ownership remains outside Agent Tools and background jobs. No D9-01
transcript export or automatic enumeration is introduced.

## Scope fence

No D2 semantic changes, V3 wire changes, new crypto, server changes, production
V3 enablement, D9-02-05 E2E or D9-03 Wear work. OD-012 remains a release gate.
The existing production composition continues to omit the optional V3 path.
