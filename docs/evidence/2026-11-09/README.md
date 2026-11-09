# Payment recovery evidence

This package checks whether uncertain simulated payments can be investigated and resolved without unintended additional authorization. It does not involve real charges, banking APIs or settlement.

## Recorded snapshot

- Measured backend revision: [`47fe5dff8fd8e0d81e55c0b4fad50bddbf3caec3`](https://github.com/vietdungnguyen2005/v-pulse-payment-reliability/commit/47fe5dff8fd8e0d81e55c0b4fad50bddbf3caec3).
- Recorded on 9 November 2026, 01:55 UTC.
- Java 21, Spring application services/MockMvc, disposable Testcontainers PostgreSQL 17.6 and Redis 8.2, controlled gateway with an authorization-call counter.
- Machine-readable observations: [JSON](recovery-results.json) and [CSV](recovery-results.csv).

## Failure matrix

| Scenario | Observed final state | Authorization calls | Other verified outcome |
|---|---|---|---|
| Accepted operation, lost response, repeated resolution | SUCCEEDED | 1 | Lookup left the payment parked; two explicit resolutions produced one resolution audit and one resolved evidence row. |
| Two actors investigate and resolve | SUCCEEDED | 1 | Investigation results shared one evidence ID; resolutions produced one audit. |
| UNKNOWN | PARKED | 1 | Resolve and replay both rejected with HTTP 409. |
| UNAVAILABLE | PARKED | 1 | Resolve and replay both rejected with HTTP 409. |
| NOT_FOUND | PARKED | 1 | Synthetic ledger entry removed for the lookup; resolve and replay both rejected with HTTP 409. |
| Provider commit survives local finalization rollback | SUCCEEDED | 1 | Original integration assertions validate recovery; one resolution audit and one resolved evidence row. |
| Permission revoked during instruction-lock wait | PARKED | 0 | Replay rejected after the permission recheck. |
| Late provider response | PARKED | 1 | Original assertions reject stale finalization; zero payment-attempt rows. |

All eight scenarios passed their assertions, with **zero unexpected additional authorization calls, duplicate resolution audits or final states differing from expectations**. Seven operator commands were correctly rejected: six for inconclusive lookup outcomes and one in the permission race. These totals describe a bounded synthetic matrix, not a real-world reliability percentage.

## Observation boundaries

The first five rows export direct before/after snapshots; the first also exports a snapshot after lookup. The last three invoke unchanged original integration methods and export their final database snapshots. Their JSON `before` is deliberately `null`: preconditions are established by assertions inside the named original methods, not independently captured here. Do not treat those entries as a complete time-series log.

The concurrent investigation starts two actors behind a gate; concurrent resolution uses two executor submissions. Actual database scheduling remains environment-dependent. The call counter tracks gateway authorization attempts, not confirmed charges. A permitted retry after verified no-execution rejection would legitimately be another attempt; it is not included as an unexpected duplicate.

The simulated provider ledger commits independently from local finalization but shares the database's availability. This is not proof of resilience to an independent financial provider's outage. No process-kill timing or natural lease-expiry recovery SLA was measured here. A separate [historical restart drill](../../fde/incident-report.md) used accelerated lease expiry and must not be presented as natural recovery time.

## Reproduce

Requirements: PowerShell 7+, Java 21, Docker engine, and initial network access for Maven/container downloads. No payment-provider credentials are needed.

From the repository root:

```powershell
pwsh -File ./tools/evidence/run-recovery.ps1
```

The [runner](../../../tools/evidence/run-recovery.ps1) copies the unchanged [PaymentReliabilityIntegrationTest](../../../backend/src/test/java/dev/vpulse/PaymentReliabilityIntegrationTest.java) and the [measurement extension](../../../tools/evidence/PortfolioRecoveryEvidenceTest.java) into a generated build. Reflection reuses private fixture helpers and three named test methods; this measurement adapter is coupled to that fixture. It does not modify product sources or the original POM.

Timestamped JSON, local logs, an attempt manifest with fixture/harness hashes and generated Surefire reports go under ignored `backend/target/portfolio-evidence/recovery/`. The runner rejects tracked backend changes, skipped/failed execution and snapshot changes. It records the current checkout SHA; reruns remain separate from the historical JSON/CSV above.

## Provenance

The recorded matrix came from an external measurement-only extension. The public extension preserves scenarios/assertions while generalizing class/environment-variable names and path handling. The repository-relative runner was also executed successfully before publication.

An earlier attempt used a synthetic merchant label longer than the application's 40-character limit. The application correctly returned HTTP 400; the fixture label was shortened and the matrix rerun. No product fix was required. That fixture error is not counted as a payment-recovery failure, nor hidden as a production success. Raw logs and private interview/job-preparation documents are not published.
