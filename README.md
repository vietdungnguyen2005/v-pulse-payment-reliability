# V-Pulse — Payment Recovery Simulator

An operator dashboard for investigating uncertain simulated payments and recording a decision without blindly authorizing the payment again.

[Live demo](https://v-pulse-payment-ops.vmarket-vietdung2005.workers.dev/) · [Recorded results](docs/evidence/2026-11-09/README.md) · [Screenshots](docs/fde/acceptance-tests.md) · [Run locally](docs/developer-guide.md)

## Problem & Solution

**Problem:** A provider can accept an operation while its response is lost. A timeout alone does not prove rejection; another authorization may repeat the action. Concurrent decisions and late responses can also overwrite recovery work.

**Solution:** V-Pulse separates **lookup, explicit resolution and controlled retry**. Operators inspect the original operation before recording a local decision. Unknown, unavailable and not-found results remain parked rather than becoming permission to authorize again.

This personal portfolio project uses a **simulated provider**—not real charges, banking integration or settlement.

## Engineering

- **Feature integration:** A review dashboard connects to Java APIs for evidence lookup, decision history and permitted actions.
- **Durable identity:** The original reference is stored before authorization. The simulator ledger commits separately from local finalization.
- **Guarded decisions:** Locks, versions and evidence bindings protect recovery. Replay permission is rechecked after acquiring the instruction lock.
- **Failure handling:** Lookup does not authorize; explicit resolution is retry-safe. Persisted investigation claims and guarded finalization handle interruption and late responses within documented limits.

**Stack:** Java 21, Spring Boot, PostgreSQL, Redis, TypeScript, Next.js/React, Docker and GitHub Actions.

## Impact & Results

**Observed local outcomes using application services, disposable PostgreSQL/Redis and a controlled gateway—not financial transactions.**

| Problem addressed | Verified result | Practical value |
|---|---|---|
| Accepted operation with a lost response | Repeated explicit resolution reached success with **one authorization call and one resolution audit** | Lookup and resolution did not repeat authorization. |
| Concurrent investigation/resolution | Two synthetic actors converged on **one evidence ID and one resolution audit**, with **one authorization call** | Competing decisions did not duplicate the action. |
| Unknown, unavailable or not-found result | All three remained parked; **six unsafe resolve/replay commands were rejected** | Inconclusive evidence did not become permission to retry. |

The fresh eight-scenario matrix also covered database-finalization failure, permission changes during lock waits and late responses. It recorded **zero unexpected additional authorization calls, duplicate resolution audits or final states differing from their defined expectations**.

[Raw snapshots, failure history and reproduction](docs/evidence/2026-11-09/README.md) distinguish direct observations from assertions inside reused tests. Scenario totals are verification scope, not a production reliability percentage.

## Explore the project

- [Evidence screenshot](docs/fde/evidence/accepted-lost-evidence.png) · [Demo walkthrough](docs/fde/customer-demo-script.md)
- [Reconciliation contract](docs/fde/payment-reconciliation-contract.md) · [Decision matrix](docs/fde/recovery-decision-matrix.md)
- [Historical process-restart exercise](docs/fde/incident-report.md) · [Operations handoff](docs/fde/operations-handoff.md)

## Scope & Demo Limits

The simulator ledger commits independently but shares PostgreSQL availability; it is not an independent financial service. A permitted retry after a verified no-execution rejection is a new attempt, not an unexpected duplicate.

The historical restart drill accelerated lease expiry. No natural recovery-time SLA, throughput improvement or universal exactly-once guarantee is claimed. The free-hosted backend may cold-start; operator actions require a provisioned account. Credentials are not published.

Former repository name: Project3; the live-demo URL is unchanged.
