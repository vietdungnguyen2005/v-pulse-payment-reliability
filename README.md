# V-Pulse — Safe Payment Investigation

An operations dashboard for investigating uncertain **simulated** payments, reviewing provider evidence and resolving confirmed outcomes without blindly submitting the payment again.

[Live demo](https://v-pulse-payment-ops.vmarket-vietdung2005.workers.dev/) · [Incident results](docs/fde/incident-report.md) · [Evidence and screenshots](docs/fde/acceptance-tests.md) · [Run locally](docs/developer-guide.md)

## Problem & Solution

**Problem:** A payment provider can accept an authorization while its response is lost. The application sees a timeout, but that does not mean the provider rejected it. Pressing “retry” can submit another authorization. Operators need to know what happened, what action is safe and who approved it.

**Solution:** V-Pulse separates three actions:

1. **Investigate:** Look up the original operation using its saved provider reference.
2. **Review and confirm:** Record a local success only when evidence verifies it.
3. **Retry only when permitted:** A separate, explicit action is available only when the simulator establishes a safe path.

Unknown, unavailable and “not found” results remain blocked. **Lookup and confirmation never submit a new authorization.**

<details>
<summary>View the provider-evidence screenshot</summary>

![Provider evidence awaiting explicit operator confirmation](docs/fde/evidence/accepted-lost-evidence.png)

</details>

## Engineering

- **Original-operation identity:** A stable reference is saved before each authorization attempt. A separate simulator ledger preserves the provider's result even if the application's result cannot be saved.
- **Separate observation and action:** Provider evidence is stored before an operator can confirm a resolution. The UI displays the permitted next action and requires explicit confirmation.
- **Concurrent decision safety:** Database locks and version checks prevent two operators—or an outdated screen—from applying conflicting decisions.
- **Crash recovery:** Investigation claims are saved before lookup. After interruption, an operator can safely look up the original operation again.
- **Failure containment:** Response timeouts, per-provider concurrency limits and circuit breakers contain an unhealthy dependency rather than recursively retrying it.
- **Traceability:** Operator sessions, access checks, action history, request identifiers and metrics make the recovery decision inspectable.

**Core stack:** Java 21, Spring Boot, PostgreSQL, Redis, React/Next.js and TypeScript. Docker and GitHub Actions support repeatable delivery.

## Impact & Results

These are **observed outcomes from a local simulated incident exercise**, not real charges, settlement results or unit-test counts. Recorded on 24 October 2026 using the real application, browser and disposable databases.

| Situation | Measured outcome | Why it matters |
| --- | --- | --- |
| Provider accepts, but the application receives no response | Investigation and explicit confirmation resolve the payment with **1 total provider authorization call** | Recovery adds **0 authorization calls**, avoiding a second submission in this scenario. |
| Two operators' requests investigate and confirm concurrently | **1 investigation ID, 1 resolution-history event and 1 provider call** | Concurrent requests reach one consistent decision without duplicate authorization. |
| Unknown, unavailable or not-found provider result | **All 3 outcome types remain parked; replay is refused** | Missing or inconclusive evidence does not become permission to submit another authorization. |
| Application process is killed during investigation | Restart preserves the interrupted history and resolves the original payment with **1 total provider call** | Investigation can recover without repeating the financial-like action. |
| Provider definitively rejects without execution | **1 rejected call + 1 separately approved new attempt** | A deliberate permitted retry is distinguishable from an automatic duplicate. |

**Evidence:** [Machine-readable incident outcomes](docs/fde/evidence/game-day.json) · [Timeline, screenshots and method](docs/fde/incident-report.md). The HTTP concurrency exercise uses one QA account; a separate database exercise covers distinct operator actors.

The crash scenario killed and restarted the actual Java process. Its investigation lease was deliberately expired in the disposable database, so it does **not** establish a production recovery-time promise. Results establish behavior in these scenarios—not a universal exactly-once guarantee.

## Explore the project

- **For a quick explanation:** [Customer scenario](docs/fde/customer-incident-brief.md) · [English walkthrough](docs/fde/customer-demo-script.md).
- **For engineering detail:** [Reconciliation contract](docs/fde/payment-reconciliation-contract.md) · [Recovery decision matrix](docs/fde/recovery-decision-matrix.md).
- **For reproducibility:** [Acceptance evidence](docs/fde/acceptance-tests.md) · [Developer guide](docs/developer-guide.md).
- **For incident handling:** [Operations handoff](docs/fde/operations-handoff.md) · [Runbook](docs/RUNBOOK.md).

### Demo boundaries

A personal portfolio project using a deterministic payment simulator. **No real payment credentials, charges or provider integration.** The provider ledger commits independently of local payment state but shares PostgreSQL availability; this is not an independently hosted financial system.

Cloudflare serves the frontend; Render hosts Java with Neon PostgreSQL and Upstash Redis. Free-host startup can delay initial requests. Mutation controls require a provisioned operator account; credentials are not published. Historical operations without a trustworthy reference remain manual-review cases.

---

Former repository name: `Project3`. The new name changes the repository address, not the live-demo URL.
