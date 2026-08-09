# Repeatable Replay Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the isolated Jenkins replay demo safely repeatable and archive clear `OPEN -> RESOLVED`, `target 0 -> 1`, `INSERTED` evidence without changing normal Crossref replay behavior.

**Architecture:** Reuse the deterministic fixture SQL and post-run summary. Add one guarded demo-only reset/evidence shell script, call it only inside the existing `demoJob` branch, and change the fixture/post-summary contract from target-present `NO_OP` to target-absent `INSERTED`. Keep the application replay implementation untouched.

**Tech Stack:** Jenkins Declarative Pipeline, Bash, MySQL 8.4 SQL, JUnit 5/AssertJ contract tests, Spring Batch integration tests.

---

### Task 1: Lock the demo-only contract with failing tests

**Files:**
- Modify: `src/test/java/com/heojungseok/openmetadatasync/jenkins/DemoInfrastructureContractTest.java`
- Modify: `src/test/java/com/heojungseok/openmetadatasync/jenkins/JenkinsPipelineContractTest.java`

- [ ] **Step 1: Write the failing reset/evidence assertions**

Require `scripts/demo-reset-replay.sh`, `DEMO_REPLAY_RESET_ACK=REPLAY_ERRORS`, exact port/container/source UUID guards, `replay-before-<REQUEST_ID>` JSON/Markdown, and a `BEFORE` console line. Update fixture/summary assertions to require:

```java
.contains("DEMO_TRANSIENT_WRITE")
.contains("DELETE FROM work WHERE doi = '10.5555/demo-replay';")
.contains("inserted_count")
.doesNotContain("no_op_count")
```

- [ ] **Step 2: Write the failing Jenkins isolation assertions**

Require:

```java
.contains("DEMO_REPLAY_RESET_ACK=REPLAY_ERRORS")
.contains("scripts/demo-reset-replay.sh")
.contains("replay-before-${params.REQUEST_ID}.json")
.contains("replay-after-${params.REQUEST_ID}.json")
```

Retain proof that `sourceExecutionId` starts from `params.SOURCE_EXECUTION_ID`, is overridden only inside `if (demoJob)`, and normal modes remain available.

- [ ] **Step 3: Verify RED**

Run:

```bash
./gradlew test --tests '*DemoInfrastructureContractTest' --tests '*JenkinsPipelineContractTest' --no-daemon
```

Expected: FAIL because the reset script and new artifact/inserted contracts do not exist.

### Task 2: Implement guarded reset and before evidence

**Files:**
- Create: `scripts/demo-reset-replay.sh`
- Modify: `scripts/demo-replay-fixture.sql`

- [ ] **Step 1: Change the fixed fixture**

Use:

```sql
'PERSISTENCE', 'DEMO_TRANSIENT_WRITE', 'Simulated transient write failure before target insert'
```

Reset those columns in the duplicate-key clause and replace the target upsert with:

```sql
DELETE FROM work WHERE doi = '10.5555/demo-replay';
```

- [ ] **Step 2: Add exact destructive-boundary guards**

Require request/source/database credentials and reject unless:

```bash
[[ "$DEMO_REPLAY_RESET_ACK" == "REPLAY_ERRORS" ]]
[[ "$DB_HOST" == "localhost" || "$DB_HOST" == "127.0.0.1" ]]
[[ "$DB_PORT" == "3308" ]]
[[ "$DEMO_DB_CONTAINER" == "open-metadata-sync-demo-mysql" ]]
[[ "$SOURCE_EXECUTION_ID" == "00000000-0000-0000-0000-00000000d001" ]]
```

- [ ] **Step 3: Load, verify, and expose before state**

Run the fixture against only `open_metadata`. Require:

```text
FAILED OPEN PERSISTENCE DEMO_TRANSIENT_WRITE 10.5555/demo-replay 0 0
```

Print the `BEFORE` line and write `build/jenkins/replay-before-${REQUEST_ID}.json` and `.md` with source/error status, type/code/message, DOI, replay count, and target count.

- [ ] **Step 4: Re-run focused tests**

Expected: reset assertions pass; Jenkins/after-summary assertions remain RED.

### Task 3: Update after evidence and Jenkins isolation

**Files:**
- Modify: `scripts/demo-replay-summary.sh`
- Modify: `Jenkinsfile.crossref`

- [ ] **Step 1: Change after-summary semantics**

Require fields:

```text
RESOLVED 1 0 1 1 1 COMPLETED
```

They represent error status, replay count, new error count, replay staging count, inserted count, target count, and replay status. Print:

```text
AFTER: replay=COMPLETED error=RESOLVED doi=10.5555/demo-replay replay_count=1 inserted=1 target_rows=1
```

Write `replay-after-${REQUEST_ID}.json` and `.md`.

- [ ] **Step 2: Invoke reset only inside the demo branch**

Inside the credential scope and before Java launch:

```groovy
if (demoJob) {
    withEnv([
        'DEMO_REPLAY_RESET_ACK=REPLAY_ERRORS',
        "SOURCE_EXECUTION_ID=${sourceExecutionId}",
        'DEMO_DB_CONTAINER=open-metadata-sync-demo-mysql'
    ]) {
        sh 'scripts/demo-reset-replay.sh'
    }
}
```

Do not change normal parameter construction.

- [ ] **Step 3: Archive before and after evidence**

Always include before JSON/Markdown after a successful reset. Only on a valid status-zero demo outcome, run the summary and include after JSON/Markdown.

- [ ] **Step 4: Verify GREEN and shell syntax**

Run the focused test command, then:

```bash
bash -n scripts/demo-reset-replay.sh scripts/demo-replay-summary.sh
git diff --check
```

Expected: exit 0.

### Task 4: Prove isolated runtime repeatability

**Files:**
- Runtime artifacts: `build/jenkins/replay-before-local-repeatable-replay-*`
- Runtime artifacts: `build/jenkins/replay-after-local-repeatable-replay-*`

- [ ] **Step 1: Record protected baselines**

Read counts/checksums from `open_metadata_benchmark_preflight` on port `3308` and inventory the port `3307` container without writing.

- [ ] **Step 2: Run reset and inspect BEFORE evidence**

Use the existing protected credential without printing it. Run request `local-repeatable-replay-1` and require `FAILED/OPEN/replay_count=0/target_count=0`.

- [ ] **Step 3: Run the existing application replay**

Run `crossrefSyncJob` with `mode=REPLAY_ERRORS`, source UUID `00000000-0000-0000-0000-00000000d001`, chunk/batch `1000`, profile `actual`, and port `3308`.

- [ ] **Step 4: Generate and inspect AFTER evidence**

Require `COMPLETED/RESOLVED/replay_count=1/inserted_count=1/target_count=1`.

- [ ] **Step 5: Reset again**

Run request `local-repeatable-replay-2` and require return to the exact BEFORE state.

- [ ] **Step 6: Compare protected baselines**

Require the 10K preflight schema and port `3307` inventory to be unchanged.

### Task 5: Verify, review, integrate, and run Jenkins

**Files:**
- All approved files above

- [ ] **Step 1: Run full tests**

```bash
./gradlew test --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Perform combined Gate 5/7 review**

Independently review demo-only isolation, destructive guards, evidence truthfulness, normal-path preservation, and completion boundaries. Resolve every Blocker/High finding.

- [ ] **Step 3: Commit only approved files**

Run `git diff --cached --check` and commit with:

```bash
git commit -m "feat: make replay demo repeatable"
```

- [ ] **Step 4: Fast-forward and push develop**

Require clean `develop` at the current `origin/develop`, fast-forward it, push, and verify local/remote SHA equality.

- [ ] **Step 5: Run final-SHA Jenkins proof**

Use `REQUEST_ID=jenkins-demo-replay-repeatable-20260809-1`, `MODE=REPLAY_ERRORS`, blank `SOURCE_EXECUTION_ID`, and chunk/batch `1000`. Require `Finished: SUCCESS`, matching SHA, both console lines, and four archived artifacts.

- [ ] **Step 6: Report evidence boundaries**

Separate local tests, isolated Docker runtime, and Jenkins evidence. Confirm no README, Wiki publication, Cloudflare, port `3307`, or normal Crossref behavior changed.
