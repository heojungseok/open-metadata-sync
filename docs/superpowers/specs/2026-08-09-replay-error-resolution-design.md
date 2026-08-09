# Replay Error Resolution Design

**Status:** user-approved

**Approval basis:** The user approved the bounded MVP behavior and immediate implementation, then explicitly approved the Gate 2 correction: `마이그레이션 포함한 수정안으로 진행해줘`.

## Goal

Close the manual `REPLAY_ERRORS` lifecycle without expanding it into an automated retry platform.

## Baseline

- Repository: `/Users/heojungseok/Desktop/workspace/open-metadata-sync/.worktrees/develop`
- Branch and commit: `develop@8d6dd8527162c38734f6fdaf5bf4713ff7f61c49`
- Initial state: clean and equal to `origin/develop`
- Existing behavior: replay preparation freezes all source `OPEN` errors through an `errorUpperBound`, copies their immutable staging rows, and runs `sync -> verify` without calling Crossref.
- Gap: the source errors remain `OPEN`, while their existing `replay_count` and `resolved_at` columns are never updated.

## Approved Behavior

1. A successfully committed replay preparation counts as one replay attempt for the frozen source-error set.
2. Each replay staging row durably records the exact `source_error_key` selected by the immutable snapshot.
3. Only a replay whose verification result is exactly `COMPLETED` resolves those exact source errors.
4. Resolution changes only source errors that are still `OPEN`, sets `status = RESOLVED`, and records `resolved_at`.
5. `FAILED` and `COMPLETED_WITH_ERRORS` replay executions leave source errors `OPEN`.
6. Errors outside the immutable snapshot are not incremented or resolved, regardless of later status changes.
7. Same-request restart does not increment `replay_count` twice because the completed replay-prepare Step is not rerun.
8. Final Step and Job failure logs expose the controlled verification `exitCode` and one-line `reason`, allowing an operator to distinguish a replayable business conflict from a technical restart without exposing exception messages.

## Chosen Design

Add one nullable `staging_work.source_error_key` lineage column for replay rows, a restrictive foreign key to `sync_error(error_key)`, and `UNIQUE (execution_id, source_error_key)`. MySQL permits multiple ordinary staging rows with `NULL`, permits a source error to appear in later replay executions, and rejects duplicate lineage inside one replay execution. `JpaErrorReplayPreparer` copies the exact source error key with each immutable staging row and increments attempts for those exact keys. `JpaExecutionVerifier` resolves the exact lineage in the same transaction that records a clean `COMPLETED` result; `FAILED` and `COMPLETED_WITH_ERRORS` never call resolution.

The resolution update joins the replay execution's staging rows back to their exact source errors and changes only rows still `OPEN`. This is an all-or-nothing replay-execution policy: a partially clean replay resolves none of its source errors.

## Alternatives Considered

1. **Chosen: nullable per-row lineage column.** One additive migration makes resolution exact even when statuses change after the snapshot.
2. **Execution plus frozen upper bound.** Smaller diff, but rejected at Gate 2 because an upper bound identifies a prefix rather than the exact snapshot membership.
3. **Leave source errors open and document replay as partial.** Smallest code change but leaves misleading operational state and weakens the portfolio claim.

## Failure and Recovery

- Preparation transaction rollback also rolls back replay-count increments and copied staging rows.
- Verification failure never calls resolution.
- Resolution executes in the verify Step transaction; a database error fails and rolls back that Step instead of silently completing.
- Code rollback can leave the nullable lineage column in place without affecting collect or normal sync rows. Any source errors resolved by a proven clean replay remain valid audit state.

## Verification

- RED/GREEN integration tests for clean resolution, `COMPLETED_WITH_ERRORS` and failed replay preservation, changed/late error exclusion, replay-count behavior, preparation rollback, and same-request restart.
- Existing immutable-snapshot and process-restart tests.
- Focused replay tests, then `./gradlew clean test`.

## Non-goals

- Automatic retry scheduling
- Maximum retry count or `PERMANENT_FAILURE`
- DLQ, alerts, dashboard, API, or UI
- A separate mapping table or `resolved_by_execution_id` column
- Automatic retry routing from log output

## Risk

- Impact scope: 1 — one batch mode and its source-error records
- Failure impact: 2 — false resolution could hide an unprocessed error
- Reversibility: 1 — the additive nullable column is backward-compatible, but persisted statuses require controlled repair if wrong
- Verification uncertainty: 1 — local integration behavior requires execution evidence. Jenkins execution is not a completion condition for this implementation task and remains follow-up Milestone 2 evidence.
- Total: 5, high; forced-high because incorrect state mutation can hide data-processing failures
