# Developer guide

Setup, implementation details and verification commands. For the business problem and measured outcomes, start with the [README](../README.md). Evidence files retain their original execution date; they are not continuous production measurements.

### Dependency audit policy (2026-10-18)

Run `npm run audit:dependencies`. Runtime dependencies retain a strict high/critical audit. The full audit uses `audit-ci.json` with one user-approved, exact advisory-and-lint-path exception for GHSA-vfj7-8cjw-p6xm (unpatched braces stack exhaustion), expiring at 00:00 Asia/Bangkok on 2026-11-18. This is accepted risk, not a vulnerability fix or a zero-findings claim. Lint only trusted repository configuration; do not feed untrusted root-directory glob patterns into the lint tool. All other high/critical findings and audit-service failures still block CI. Remove the exception when upstream is patched; do not renew automatically.

## Run locally

Prerequisites: Node.js 22, Java 21, and Docker.

```bash
cp .env.example .env.local
docker compose up --build -d
npm ci
npm run dev
```

Open `http://localhost:3000`; Prometheus is at `http://localhost:9090` and Grafana at `http://localhost:3001` (`admin` / `vpulse-local`). Demo seed data is enabled only by Compose; it defaults off in the application.

### Operator access

The public dashboard is read-only. Creating simulated payments, changing rail faults, and replaying parked work require an authenticated `OPERATOR` session and a CSRF token. Operators are explicitly trusted across this single demo dataset; merchant-scoped self-service accounts are not exposed. A caller cannot gain this role by choosing a `merchantId` or sending an operations service secret.

Migration V2 adds PostgreSQL accounts, login windows, and durable Spring Session tables. Set `AUTH_BOOTSTRAP_USERNAME` and `AUTH_BOOTSTRAP_PASSWORD_HASH` (BCrypt) in the **backend process environment** to provision the initial operator. Both blank means no default account. Existing accounts are not overwritten; never publish operator credentials. Spring does not automatically load Next.js `.env.local`.

Cookies are HttpOnly, SameSite=Lax and Secure by default; production rejects `AUTH_SECURE_COOKIE=false`. Set false only for local HTTP (Compose does this). Session idle timeout is 15 minutes. Disabling an account or changing its database role takes effect on its next request; there is no public signup/password recovery workflow.

Login is limited to 10 attempts/minute per backend peer address in PostgreSQL. A shared BFF may share that budget: this is not a per-end-user-IP limiter, and arbitrary forwarded-IP headers are not trusted. Use HTTPS and platform ingress limits in production.

For a real frontend → BFF → Java → PostgreSQL browser check, run backend verify and frontend build **before** this command; do not rebuild concurrently:

```powershell
pwsh -NoProfile -File tools/verify-operator-session.ps1
```

The script creates disposable local PostgreSQL/Redis containers and removes them afterwards. It does not touch production. See [local remediation evidence](../docs/evidence/remediation-2026-10-18.md); historical evidence retains its original date and deployment scope.

## Verification

### Merchant operation identity

Payment create now requires `Idempotency-Key` (8–120 ASCII identifier characters). Merchant IDs are trimmed/uppercased before lookup, fingerprinting and quota. V3 atomically stores the key/request hashes, instruction reference and received audit; historical instructions are preserved without fabricated operation identities. Matching duplicates do not call the gateway or consume a new create quota unit; changed payment details return 409. A pending duplicate returns the same instruction with 202, terminal duplicate 200, and a newly completed create 201.

The browser saves a UUID and canonical payment payload before sending, preserves them on failed/unverified/PROCESSING responses, and retries the same key after reload only on explicit submission. Edited details cannot reuse an unresolved intent. Storage failures/corruption fail closed. No login cookie, CSRF token or service credential is stored there. Form restoration/recovery guidance is still basic; deleting/cloning storage or switching devices is not account recovery. The ledger is retained with business instructions, not expired to permit re-execution.

This prevents duplicate **local create** operations, not duplicate provider settlement. The simulated provider now supports stable references, immutable outcome lookup, accepted-but-response-lost reconciliation and expiring investigation claims. Its ledger is independent of local payment state/commit but shares the same database availability domain. Existing payment stale-work recovery remains a two-minute scan, not a general durable worker lease system. Do not infer that a healthy rail makes an unknown earlier outcome safe to replay.

Before rollout: stop old writers/pause mutations, back up and apply additive migrations through V5, deploy matching backend/BFF/client, then smoke. Old clients without a key get 400; mixed-version zero-downtime rollout is not promised. Rerun dependency audit before deployment; the latest local run is a snapshot, not a permanent security guarantee. No new platform keys are needed for this local work.

```bash
npm run verify
npm run test:e2e
cd backend && ./mvnw verify
docker compose config --quiet
helm lint infra/helm/vpulse
```

The local browser suite starts on `127.0.0.1:3207`; set `PLAYWRIGHT_PORT` to override that port or `PLAYWRIGHT_BASE_URL` to test an existing deployment.

See [pain-point evidence](../docs/PAIN_POINT_PROOF.md), [architecture decisions](../docs/ARCHITECTURE.md), [incident runbook](../docs/RUNBOOK.md), and the [game-day postmortem](../docs/POSTMORTEM.md).

