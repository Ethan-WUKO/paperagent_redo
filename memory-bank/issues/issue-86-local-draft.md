# implementation: 让独立文献检索优先复用已有 LiteratureCard

> Status: local draft pending GitHub sync
> Reason: `gh issue create` blocked by current network (`Post https://api.github.com/graphql: EOF`)

## Background

Roadmap Phase 5 requires literature-card reuse to happen not only after task completion, but also earlier in the retrieval chain so repeated standalone searches can benefit from existing structured assets instead of always depending on external sources first.

Current code evidence:

- `LiteratureSearchTaskWorker` now materializes standalone search results into reusable `LiteratureCard` rows after external retrieval completes.
- `AdHocLiteratureSearchService` still always fans out to external `LiteratureSource`s and only deduplicates in-memory for the current run.
- Existing `LiteratureCard` rows already carry DOI/arXiv/OpenAlex/title-hash identities plus abstract/venue/year metadata that can seed future standalone task results.

## Goal

- let standalone literature search prefer matching existing `LiteratureCard` assets before or alongside external source fan-out
- preserve current result ranking and task lifecycle semantics while reducing repeated external lookups for already-known papers
- keep the reuse policy aligned with the existing DOI/arXiv/OpenAlex/S2/title-hash identity rules

## Non-goals

- no full search-index or vector recall redesign in this issue
- no project/workspace binding changes
- no paper-task retrieval rewrite beyond the shared reuse path if needed
- no frontend redesign beyond any minimal payload fields required by the backend change

## Impacted modules

- `yanban-paper` standalone literature search flow
- `LiteratureCard` lookup/reuse helper layer
- focused tests around standalone retrieval reuse and ranking behavior

## Design notes

- Prefer querying existing `LiteratureCard` records through a shared catalog/search helper before external source fan-out, or merge local hits into the same candidate pipeline.
- Preserve degraded behavior: if local reuse is insufficient, external sources must still run and backfill missing metadata.
- Make the precedence/ranking strategy explicit so older cached cards do not drown out better fresh results.

## Acceptance criteria

- repeated standalone searches can reuse already-known `LiteratureCard` data without relying solely on new external source responses
- reused local-card hits still produce the same standalone task result shape, including stable `cardId`
- external retrieval still runs when local reuse is insufficient, and newly found papers still materialize into cards
- existing paper-task retrieval behavior is not regressed

## Tests

- focused tests covering local-card reuse in standalone search results
- focused tests covering fallback to external sources when local matches are absent or incomplete
- regression coverage for card identity consistency and result payload shape

## Risks

- stale cached card metadata may need freshness rules or conservative ranking so low-quality local hits do not dominate
- local reuse must not create a second identity policy separate from the card catalog helper

## Rollback plan

- revert the local-card reuse hook and keep standalone literature search on the current external-first behavior while retaining post-task card materialization
