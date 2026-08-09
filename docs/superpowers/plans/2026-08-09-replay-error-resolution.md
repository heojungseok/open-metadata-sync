# Replay Error Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close successful manual replay executions by updating the exact frozen source-error set while preserving failed and late errors.

**Architecture:** Persist the exact source error key on each copied replay staging row. Account those exact keys inside replay preparation and resolve them only after a clean verifier result in the same verify Step transaction.

**Tech Stack:** Java 21, Spring Batch, JPA/Hibernate, MySQL, JUnit 5, Testcontainers

---

### Task 1: Lock the replay lifecycle with failing integration tests

**Files:**
- Modify: `src/test/java/com/heojungseok/openmetadatasync/batch/job/DataPlaneBenchmarkJobConfigTest.java`
- Modify: `src/test/java/com/heojungseok/openmetadatasync/batch/OpenMetadataSyncJobIntegrationTest.java`
- Modify: `src/test/java/com/heojungseok/openmetadatasync/schema/SchemaContractTest.java` only if its explicit assertions require the new column or foreign key

- [x] Extend the clean replay test to assert that the frozen source error becomes `RESOLVED`, `resolved_at` is non-null, and `replay_count` is one.
- [x] Extend conflict and validation-only replay tests to assert that their source errors remain `OPEN` while `replay_count` is one.
- [x] Extend the immutable snapshot test to assert exact `source_error_key` lineage and that changed or late nonmembers are neither counted nor resolved.
- [x] Add preparation rollback and same-request restart assertions proving `replay_count` is respectively zero and one.
- [x] Run the focused tests and confirm they fail because source error lifecycle mutation is missing.

Run:

```bash
./gradlew test --tests 'com.heojungseok.openmetadatasync.batch.job.DataPlaneBenchmarkJobConfigTest' --tests 'com.heojungseok.openmetadatasync.batch.OpenMetadataSyncJobIntegrationTest'
```

Expected: test assertion failures showing the lineage column is absent, source errors remain `OPEN`, or `replay_count = 0`.

### Task 2: Implement the minimum lifecycle mutation

**Files:**
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/replay/JpaErrorReplayPreparer.java`
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/verify/JpaExecutionVerifier.java`
- Create: `src/main/resources/db/migration/V5__replay_error_lineage.sql`

- [x] Add nullable `staging_work.source_error_key`, `UNIQUE (execution_id, source_error_key)`, and a restrictive foreign key to `sync_error.error_key`.
- [x] Verify that ordinary NULL staging rows and the same source error in different replay executions succeed while a duplicate source error inside one replay execution fails.
- [x] In `prepare`, copy `errorKey` into each replay staging row and increment `replay_count` for the exact copied keys in the same transaction.
- [x] In `JpaExecutionVerifier`, join replay staging lineage to still-`OPEN` source errors and mark them `RESOLVED` with the verification completion timestamp.
- [x] Invoke finalization only when mode is `REPLAY_ERRORS` and verifier business status is exactly `COMPLETED`.
- [x] Run the focused tests and confirm they pass.

Run:

```bash
./gradlew test --tests 'com.heojungseok.openmetadatasync.batch.job.DataPlaneBenchmarkJobConfigTest' --tests 'com.heojungseok.openmetadatasync.batch.OpenMetadataSyncJobIntegrationTest'
```

Expected: `BUILD SUCCESSFUL` with clean, failed, late-error, and restart replay assertions passing.

### Task 3: Verify scope and regression safety

**Files:**
- Review: production and test diff from Tasks 1 and 2
- Review: `docs/superpowers/specs/2026-08-09-replay-error-resolution-design.md`

- [x] Run `git diff --check`.
- [x] Run `./gradlew clean test` and record the actual test count and exit status.
- [x] Confirm only the single additive lineage migration entered the diff and no retry scheduler, DLQ, API, UI, or unrelated refactor was added.
- [x] Perform the required implementation/data/test review and address all Blocker or High findings.
- [x] Leave commit, push, branch promotion, Jenkins execution evidence, Crossref 100k E2E, and Wiki publication for the separately tracked final Milestone 2 sequence.

### Task 4: Expose the operator replay decision in failure logs

**Files:**
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/observability/BatchLifecycleLoggingListener.java`
- Modify: `src/test/java/com/heojungseok/openmetadatasync/batch/observability/BatchLifecycleLoggingListenerTest.java`
- Modify: `README.md`

- [x] Add a failing test requiring `exitCode=FAILED` and `reason=Conflict_remains_OPEN` on verify Step and final Job failure records.
- [x] Add the same fields to existing failure records while replacing exception-backed descriptions with `TECHNICAL_EXCEPTION`.
- [x] Keep only the first controlled reason line so injected line breaks and later text do not enter the log record.
- [x] Run the focused listener test and the full regression suite.

## Verification result

- Focused RED: 35 tests, 7 expected failures caused by missing lineage/lifecycle behavior.
- Focused GREEN after Gate 5 corrections: replay integration 15/15 and the three-class focused suite passed.
- Gate 5: data/code and spec/test reviewers PASS with no remaining Blocker, High, or Medium findings.
- Gate 6 before the logging addition: `./gradlew clean test --rerun-tasks` exit `0`, 123 tests, 0 failures/errors/skipped, `BUILD SUCCESSFUL` in 1m 50s.
- Final Gate 6 after the logging addition: focused listener suite 5/5, then `./gradlew clean test --rerun-tasks` exit `0`, 124 tests, 0 failures/errors/skipped, `BUILD SUCCESSFUL` in 1m 51s.
- Scope: one additive V5 migration, three production Java files, four test files, README, this plan, and its design spec. Commit, push, Jenkins execution evidence, Crossref 100k E2E, final Wiki publication, and `develop -> main` remain separate.
