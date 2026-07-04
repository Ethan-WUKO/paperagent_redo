# Local Merge Gate

This document defines the default local checks before a change is merged.

The goal is not to run every possible test for every small change. The goal is to make each issue verifiable, repeatable, and honest about risk.

## Always Run

Run these for every PR:

```powershell
git status --short
git diff --check
```

Before pushing, also confirm the target repository:

```powershell
git remote -v
git branch --show-current
```

The expected remote for this project is:

```text
https://github.com/Ethan-WOKO/paperagent_redo.git
```

## Docs-only Changes

For Markdown, GitHub templates, process docs, and planning docs:

```powershell
git diff --check
```

Full backend or frontend builds are optional for docs-only changes. If skipped, the PR must say they were skipped because no runtime code changed.

## Backend Baseline

For backend code, Maven config, migrations, model/runtime logic, RAG, paper, knowledge, auth, or API changes:

```powershell
mvn -q -DskipTests validate
```

Run focused tests for the changed module:

```powershell
mvn -pl yanban-core test
mvn -pl yanban-knowledge test
mvn -pl yanban-paper test
mvn -pl yanban-api test
```

For broad or cross-module changes, run:

```powershell
mvn test
```

If full `mvn test` cannot run, the PR must include:

1. The exact failure or reason it was skipped.
2. The focused tests that did run.
3. The residual risk.
4. The follow-up plan.

## Frontend Baseline

For frontend code, route, UI state, API client, or build config changes:

```powershell
cd frontend
$env:CI='true'
pnpm build
```

`CI=true` avoids non-interactive pnpm failures when dependencies need to be recreated.

If a UI workflow changes, include manual verification notes or screenshots when useful.

## Database and Migration Changes

For Flyway migrations or entity/repository changes:

```powershell
mvn -pl yanban-api test
```

At minimum, verify:

1. MySQL migration naming and order.
2. H2 test migration compatibility when applicable.
3. Existing repository tests still pass.
4. Rollback or forward-fix plan is documented.

## Agent, RAG, Tool, and Memory Changes

For Agent, Harness, tool calling, RAG, literature recommendation, paper polishing quality, or memory changes, unit tests are not enough.

Required checks should include:

1. Focused unit or integration tests for touched services.
2. Eval cases when behavior quality can change.
3. Verification that the final user-visible answer is not duplicated.
4. Verification that tool trace does not pollute chat bubbles.
5. Verification that failure/degraded output is understandable.

Until a formal eval runner exists, include a small manual eval table in the PR.

## Long-running Task Changes

For paper polishing, literature search, Kafka task dispatch, task lifecycle, cancellation, or event-stream changes, verify:

1. Task creation returns a task id.
2. Task status can be queried.
3. Cancel or stop request is accepted.
4. Cancelled tasks do not retry automatically.
5. Partial artifacts are marked as partial or cancelled.
6. Reconnect or refresh does not create duplicate final answers.

## PR Reporting

Every PR must report:

```text
Commands run:
- ...

Skipped checks:
- ...

Reason:
- ...

Residual risk:
- ...
```

Do not write "tests passed" without listing the exact commands.

