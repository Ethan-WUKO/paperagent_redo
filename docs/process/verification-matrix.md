# Verification Matrix

Use this matrix to choose checks for each issue. A PR may add stronger checks when risk is higher.

| Change type | Required verification | Optional or risk-based verification |
| --- | --- | --- |
| Docs only | `git diff --check` | Link preview or manual read-through |
| GitHub templates | `git diff --check` | Create a test issue/PR only if necessary |
| Maven pom/config | `mvn -q -DskipTests validate` | `mvn test` |
| Backend service | Focused module tests | Full `mvn test` |
| API/controller | Controller or integration tests | Manual API smoke test |
| Flyway migration | API module tests and H2 compatibility when applicable | Real MySQL migration check |
| Frontend UI | `$env:CI='true'; pnpm build` | Browser/manual workflow check |
| Frontend state machine | Build plus focused logic/manual state verification | Screenshot or short screen recording |
| Agent runtime | Focused service tests and manual/eval cases | End-to-end chat run |
| RAG retrieval | Retrieval eval cases | Real ES/embedding environment test |
| Literature recommendation | Authenticity and dedup eval cases | External source smoke test |
| Paper polishing | Focused paper tests and artifact checks | End-to-end paper task run |
| Kafka task dispatch | Producer/consumer focused tests | Local Docker Kafka smoke test |
| SSE/event stream | Event sequence and terminal-state tests | Reconnect/manual browser check |
| Cancellation | Cancel status and partial artifact checks | Long-running manual cancel scenario |

## Default Commands

Backend validation:

```powershell
mvn -q -DskipTests validate
```

Backend full test:

```powershell
mvn test
```

Frontend build:

```powershell
cd frontend
$env:CI='true'
pnpm build
```

Whitespace check:

```powershell
git diff --check
```

## Merge Rule

A PR can merge only when one of these is true:

1. The required verification passed.
2. A skipped check is explicitly justified, the risk is acceptable, and a follow-up issue exists when needed.

## Phase Discipline

Before implementation starts, confirm:

1. Which roadmap phase the issue belongs to.
2. Whether it is a design, spike, implementation, test, or docs issue.
3. Which non-goals prevent scope creep.

