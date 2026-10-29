# Phase 11 shared-state design

**Status:** deferred by owner on 29 October 2026. After initially approving local implementation, the owner chose to keep infrastructure simple and record backend persistence as an open limitation. No database was provisioned and no PostgreSQL code or dependency was added. This document is a future design only; implementation requires renewed owner authorization. The phone library, files and local search do not need PostgreSQL.

## Recommendation

Use PostgreSQL for persistent runtime revocations, one-time challenge consumption, and shared rolling-window budgets. It provides cross-process transactions and row locking using the standard SQL mechanisms documented by [PostgreSQL `INSERT`](https://www.postgresql.org/docs/18/sql-insert.html) and [explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html). This is provider-neutral at the application boundary; it does not assume a Render database exists or that the current free web service includes one.

`server/render.yaml` declares a Node 22 web service only. `server/package.json` has no SQL client, so `pg` would be a new production dependency. Add `pg` only after owner approval. Do not install packages, create a database, or update host configuration as part of design review.

## State and limits that must remain

- Runtime revocations persist and apply in every server instance. Existing `REVOKED_DEVICE_IDS` remains an operator-provided deny list loaded at startup; it is combined with stored revocations. No runtime un-revoke behavior is added.
- Challenge nonces are single-use across restarts and instances. Keep existing challenge validity (5 minutes), future skew (1 minute), nonce replay-history retention (10 minutes), and 20,000-entry admission cap.
- Rate limits remain rolling windows with the current configured periods and costs: the device/provider request limits, registration IP/key/global limits, provider global 120/minute and daily 1,000 calls, and embedding cost equal to the number of provider calls. Preserve the 10,000 live identity cap per limiter and fail-closed admission.
- DB connection/configuration failure never falls back to process-local revocation, nonce, or rate state. A database outage rejects affected auth/rate-checked requests with a fixed service-unavailable response and a sanitized diagnostic; it must not permit the protected operation.

## Minimal schema

All timestamps are UTC `timestamptz`. Migration is a standalone SQL file under `server/migrations/`, versioned and applied explicitly as a deployment prerequisite; server startup must not mutate schema. No request bodies, provider inputs, signing credentials, or raw device IDs are stored.

```sql
CREATE TABLE revoked_devices (
  device_hash bytea PRIMARY KEY CHECK (octet_length(device_hash) = 32),
  revoked_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE consumed_nonces (
  nonce_hash bytea PRIMARY KEY CHECK (octet_length(nonce_hash) = 32),
  consumed_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL
);
CREATE INDEX consumed_nonces_expiry_idx ON consumed_nonces (expires_at);

CREATE TABLE state_caps (
  name text PRIMARY KEY,
  entry_count integer NOT NULL CHECK (entry_count >= 0),
  max_entries integer NOT NULL CHECK (max_entries > 0)
);
INSERT INTO state_caps (name, entry_count, max_entries)
VALUES ('consumed_nonces', 0, 20000), ('revoked_devices', 0, 20000);

CREATE TABLE rate_buckets (
  limiter text NOT NULL,
  identity_hash bytea NOT NULL CHECK (octet_length(identity_hash) = 32),
  last_seen timestamptz NOT NULL,
  PRIMARY KEY (limiter, identity_hash)
);
CREATE INDEX rate_buckets_last_seen_idx ON rate_buckets (limiter, last_seen);

CREATE TABLE rate_events (
  limiter text NOT NULL,
  identity_hash bytea NOT NULL,
  event_id bigint GENERATED ALWAYS AS IDENTITY,
  occurred_at timestamptz NOT NULL,
  PRIMARY KEY (limiter, identity_hash, event_id),
  FOREIGN KEY (limiter, identity_hash)
    REFERENCES rate_buckets (limiter, identity_hash) ON DELETE CASCADE
);
CREATE INDEX rate_events_window_idx ON rate_events (limiter, identity_hash, occurred_at);

CREATE TABLE rate_scopes (
  limiter text PRIMARY KEY,
  identity_count integer NOT NULL CHECK (identity_count >= 0),
  max_identities integer NOT NULL CHECK (max_identities > 0)
);
```

`identity_hash` and `device_hash` are HMAC-SHA-256 using a dedicated, stable `STATE_HASH_SECRET`; this avoids writing raw device IDs or IP/key identities and avoids plain hash dictionary lookup. Losing/changing that secret makes existing hashed entries unreachable and is a state migration event, so it must be backed up and maintained as a host secret. `nonce_hash` is SHA-256 over the complete already-signed nonce; the nonce contains high-entropy random material. `limiter` is a fixed internal allowlist value, never client input. Scope rows are inserted for every limiter from code/migration with `max_identities=10000`.

## Atomic operations

### Revocation

At process start, parse configured IDs and HMAC them with `STATE_HASH_SECRET`; keep these in the existing local configured-deny set. Before accepting a device-authenticated operation, check configured IDs and the shared `revoked_devices` row for that device hash. The authenticated revoke endpoint locks the `revoked_devices` cap row, checks for an existing hash, rejects a new entry at 20,000, and inserts plus increments the count in one transaction. Success is durable before returning success. A DB read/write failure fails closed. Runtime revoke has no delete/unrevoke endpoint. Configured IDs remain separately governed by the existing environment value; do not copy them into DB on startup or double-count them against the runtime cap.

### Nonce consumption and cap

After signature/timestamp validation, run one short transaction:

1. Lock the `state_caps` row for `consumed_nonces` with `SELECT ... FOR UPDATE`.
2. Remove expired nonce rows in a bounded batch, decrementing `entry_count` by the exact number deleted. Repeat cleanup across later consume attempts; do not scan all nonce rows.
3. If still at 20,000 entries, reject admission. Otherwise insert `nonce_hash` with `expires_at = consumed_at + 10 minutes` using `ON CONFLICT DO NOTHING`.
4. Increment `entry_count` only if the insert succeeded; commit. A conflict means replay and fails. The cap-row lock serializes nonce admissions across processes, so concurrent replay has one winner and the cap cannot be exceeded.

For stale rows beyond the cleanup batch, add a small periodic cleanup task that uses the expiry index and `FOR UPDATE SKIP LOCKED` (or `DELETE ... RETURNING`) with a fixed row limit; decrement the same cap row in the cleanup transaction. Do not run an unbounded expiry delete on each request. Cleanup is availability/space maintenance; admission remains fail closed if the cap has not yet been freed.

### Shared rate windows and identity cap

For each configured limiter check, use a transaction scoped to `(limiter, identity_hash)`:

1. If the bucket exists, lock it `FOR UPDATE`; if it does not, lock that limiter's `rate_scopes` row, recheck for a concurrent insert, and create the bucket only when `identity_count < 10000`, incrementing the count in the same transaction. If full, deny a new identity. Lock order is always scope then bucket when both are needed.
2. Delete only this bucket's events with `occurred_at <= now - window`; then count its still-live events. If `live_count + cost` exceeds the configured cap, deny without inserting events. Otherwise insert `cost` events at the current DB timestamp, update `last_seen`, and commit. The bucket lock serializes same-identity checks, preserving exact rolling-window limits; distinct identities proceed concurrently after the short admission path.
3. Use DB time for event timestamps and expiry comparisons to avoid process clock skew across instances. Existing `RateLimiter.checkRateLimit(key, cost)` becomes async and callers await the result before protected work/provider calls. Invalid costs continue to fail closed.

Expired identities other than the key being checked are removed by a bounded periodic cleanup worker, not by a full-key request scan. For each limiter, lock its scope row first, then select a small batch of stale buckets using the `last_seen` index and `FOR UPDATE SKIP LOCKED`, delete them (cascading their events), and decrement the scope `identity_count` in the same transaction. This preserves the scope-before-bucket lock order used for identity admission. Cleanup must only remove buckets with no event newer than the maximum active window and must recheck that predicate while locked. Until cleanup removes expired buckets, they count toward 10,000 and new identities fail closed. No live budget is evicted.

The daily limiter's current 24-hour window remains a rolling 24 hours, not a UTC calendar-day counter. Provider cost is charged as today: one unit per underlying provider call, including each text in an embedding batch.

## Local implementation and test plan

- Add a small `pg` pool-backed state adapter, typed interfaces for async revoke/nonce/rate operations, and explicit SQL migration. Keep provider and Android APIs unchanged except DB-unavailable requests use fixed service-unavailable errors.
- Convert all route/auth callers to await shared-state checks. Ensure provider capacity reservations/settlement and existing request cancellation handling are preserved; rate admission occurs before provider invocation.
- Add tests against real local PostgreSQL, not only mocks: restart persistence for revocations/nonces/budgets; two independent pools/processes; parallel same-nonce consumption with exactly one success; parallel same-key requests that never exceed the limit; cost-based batches; exact expiry boundary; nonce and identity caps under concurrent admission; bounded cleanup and counter reconciliation; DB unavailable/transaction failure is fail closed; no secrets/raw IDs/provider data in logs.
- CI may use a PostgreSQL service only after the owner approves that test infrastructure and the repository CI approach is updated. A fake SQL adapter is useful for unit-level route behavior but cannot establish SQL locking or shared atomicity.
- Use local PostgreSQL for integration testing only if already available/authorized. Availability is unknown and must be checked before implementation. No network/database discovery or provisioning is part of this design task.

## Deferred implementation

The owner explicitly deferred this work after reviewing the distinction between local phone data and online-backend security state. No PostgreSQL work is currently authorized. If resumed later, confirm the store/access model and permission for local implementation/testing, including the new client dependency and configuration. Provisioning, payment and deployment require their own authorization. Until then, runtime revocations, consumed nonces and rate budgets remain process-local; those durability findings stay open.

## Known tradeoffs and limits

- Exact sliding windows require one transaction and event writes per unit of cost; this is more write-intensive than approximate fixed-window counters but preserves existing semantics.
- The nonce cap row serializes nonce admissions globally. It is bounded and simple, but at high registration throughput it can become a contention point; replacing it requires a separately reviewed exact-cap design.
- `rate_scopes` admission and periodic cleanup maintain exact 10,000-identity accounting. Cleanup may lag, temporarily denying new identities during an outage or backlog; existing identities continue to be evaluated safely.
- Hash-key rotation requires a coordinated migration/re-hash strategy. Do not rotate the secret without migrating stored hashes.
- Database HA, backups, retention, TLS, credentials, and outage behavior depend on the owner-selected provider and are deployment-time responsibilities. This design makes no claim about their availability or cost.
