# Human-readable Batch Lifecycle Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve stable `BATCH_*` search fields while adding Korean lifecycle labels and trustworthy Job/Step counter summaries that are readable in Jenkins.

**Architecture:** Keep `BatchLifecycleLoggingListener` as the single lifecycle log owner. Read identity and status from `JobExecution`, read per-step counters from `StepExecution`, aggregate Job totals from `JobExecution.getStepExecutions()`, and move periodic progress evaluation to the next chunk boundary so only committed counters are reported.

**Tech Stack:** Java 21, Spring Batch 6, SLF4J, JUnit 5, AssertJ, Gradle

---

## File map

- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/observability/BatchLifecycleLoggingListener.java` — bilingual lifecycle lines, counter formatting and Job aggregation, correct progress timing.
- Modify: `src/test/java/com/heojungseok/openmetadatasync/batch/observability/BatchLifecycleLoggingListenerTest.java` — exact output, aggregation, throttling, and secret-safety contracts.
- Modify: `README.md` — operator-facing event examples and counter meanings.
- Modify: `docs/superpowers/specs/2026-08-08-human-readable-batch-logging-design.md` — preserve Step identity in the representative line.

### Task 1: Pin bilingual lifecycle and Job aggregation contracts

**Files:**
- Test: `src/test/java/com/heojungseok/openmetadatasync/batch/observability/BatchLifecycleLoggingListenerTest.java`

- [ ] **Step 1: Replace the lifecycle test expectations and add multiple Step counters**

Set all supported counters on `sync`, add a second `prepare` Step to the Job, and expect exact bilingual output. The expected Job total must be the arithmetic sum of both Step executions.

```java
StepExecution prepare = step(22, "prepareCrossrefExecution", job);
prepare.setCommitCount(1);
job.addStepExecution(prepare);

StepExecution sync = step(21, "sync", job);
sync.setCommitCount(3);
sync.setReadCount(12);
sync.setWriteCount(10);
sync.setFilterCount(2);
sync.setRollbackCount(1);
sync.setReadSkipCount(1);
sync.setProcessSkipCount(2);
sync.setWriteSkipCount(3);
job.addStepExecution(sync);
```

Expect these representative lines exactly:

```text
BATCH_JOB_START [배치 시작] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11
BATCH_STEP_END [sync 단계 종료] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 | status=COMPLETED | 읽음=12 | 저장=10 | 걸러냄=2 | 커밋=3 | 롤백=1 | 스킵=6
BATCH_JOB_END [배치 종료] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 | status=COMPLETED | 읽음=12 | 저장=10 | 걸러냄=2 | 커밋=4 | 롤백=1 | 스킵=6
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew test --tests com.heojungseok.openmetadatasync.batch.observability.BatchLifecycleLoggingListenerTest
```

Expected: exit `1`; exact lifecycle assertions fail because the listener has no Korean labels, no extended counters, and no Job aggregation.

### Task 2: Implement the minimal bilingual lifecycle formatter

**Files:**
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/observability/BatchLifecycleLoggingListener.java`
- Test: `src/test/java/com/heojungseok/openmetadatasync/batch/observability/BatchLifecycleLoggingListenerTest.java`

- [ ] **Step 1: Add Korean labels without replacing identity keys**

Use these event shapes:

```java
log.accept("BATCH_JOB_START [배치 시작] " + jobFields(job));
log.accept("BATCH_STEP_START [단계 시작] " + stepFields(step));
log.accept("BATCH_STEP_END [" + step.getStepName() + " 단계 종료] " + stepFields(step)
		+ " | status=" + step.getStatus() + " | " + counters(step));
log.accept("BATCH_JOB_END [배치 종료] " + jobFields(job)
		+ " | status=" + job.getStatus() + " | " + counters(job));
```

Failure and chunk events follow the same pattern while retaining exception type only:

```java
"BATCH_JOB_FAILURE [배치 실패] "
"BATCH_STEP_FAILURE [" + step.getStepName() + " 단계 실패] "
"BATCH_CHUNK_ERROR [" + step.getStepName() + " 청크 실패] "
```

- [ ] **Step 2: Format all Step counters and aggregate Job totals**

Keep one formatter for the final human-readable counter portion:

```java
private static String counters(long read, long write, long filter, long commit, long rollback, long skip) {
	return "읽음=" + read
			+ " | 저장=" + write
			+ " | 걸러냄=" + filter
			+ " | 커밋=" + commit
			+ " | 롤백=" + rollback
			+ " | 스킵=" + skip;
}
```

The Step overload delegates with `step.getReadCount()`, `getWriteCount()`, `getFilterCount()`, `getCommitCount()`, `getRollbackCount()`, and `getSkipCount()`. The Job overload loops once over `job.getStepExecutions()`, sums those six values, and delegates to the same formatter.

- [ ] **Step 3: Run the focused test and verify the bilingual lifecycle GREEN**

Run the Task 1 command again.

Expected: lifecycle and aggregation assertions pass. Progress assertions may still fail until Task 3 is complete.

### Task 3: Remove misleading zero progress and retain throttling

**Files:**
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/observability/BatchLifecycleLoggingListener.java`
- Test: `src/test/java/com/heojungseok/openmetadatasync/batch/observability/BatchLifecycleLoggingListenerTest.java`

- [ ] **Step 1: Make the focused test model the real callback ordering**

Call `beforeChunk` with the number of already committed chunks in `StepExecution`. Assert that commit count zero emits no progress, commit count 100 emits one Korean progress line, and elapsed 60 seconds emits the next line.

```java
step.setCommitCount(0);
listener.beforeChunk(new Chunk<>());
listener.afterChunk(new Chunk<>());
assertThat(logs).noneMatch(log -> log.startsWith("BATCH_CHUNK_PROGRESS"));

step.setCommitCount(100);
step.setReadCount(1000);
step.setWriteCount(1000);
listener.beforeChunk(new Chunk<>());
```

Expected progress:

```text
BATCH_CHUNK_PROGRESS [sync 진행] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 | 읽음=1000 | 저장=1000 | 걸러냄=0 | 커밋=100 | 롤백=0 | 스킵=0
```

- [ ] **Step 2: Verify the new progress test fails against the old callback**

Run the focused test.

Expected: exit `1`; the old listener logs progress from `afterChunk` and still permits the first zero-counter line.

- [ ] **Step 3: Evaluate progress before the next chunk starts**

At the beginning of `beforeChunk`, read `step.getCommitCount()` as completed work. Log only when it is greater than zero and either divisible by 100 or at least 60 seconds after the previous progress line. Then record `attemptedChunks` as `completed + 1`. Reduce `afterChunk` to successful-attempt cleanup.

```java
long completed = step.getCommitCount();
Instant now = clock.instant();
Instant previous = lastProgress.getOrDefault(step.getId(), now);
if (completed > 0 && (completed % 100 == 0 || !now.isBefore(previous.plus(PROGRESS_INTERVAL)))) {
	log.accept("BATCH_CHUNK_PROGRESS [" + step.getStepName() + " 진행] "
			+ stepFields(step) + " | " + counters(step));
	lastProgress.put(step.getId(), now);
}
attemptedChunks.put(step.getId(), completed + 1);
```

- [ ] **Step 4: Run the focused test and verify full GREEN**

Run the focused test.

Expected: exit `0`; all `BatchLifecycleLoggingListenerTest` cases pass.

### Task 4: Document, verify, review, and publish

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-08-08-human-readable-batch-logging-design.md`

- [ ] **Step 1: Document operator search and counter semantics**

Add a short README paragraph stating that Jenkins operators can search for `BATCH_JOB_START`, `BATCH_STEP_END`, `BATCH_JOB_END`, and `BATCH_*_FAILURE`; Korean labels are human-facing while English identity keys remain stable. State that Job totals are literal sums of Spring Batch Step counters and include tasklet commits.

- [ ] **Step 2: Run final local verification**

```bash
./gradlew clean test
git diff --check
git status --short --branch
```

Expected: Gradle exit `0`, 115 or more tests with zero failures/errors/skips, no whitespace errors, and only the approved listener/test/README/spec/plan files changed after the design commit.

- [ ] **Step 3: Obtain one independent observability/infrastructure review**

Review the fixed diff for counter accuracy, callback timing, secret safety, bilingual search stability, both Crossref and benchmark listener wiring, and actual Jenkins proof boundaries. Block on unresolved Blocker or High findings.

- [ ] **Step 4: Commit and push the verified implementation**

```bash
git add README.md \
  src/main/java/com/heojungseok/openmetadatasync/batch/observability/BatchLifecycleLoggingListener.java \
  src/test/java/com/heojungseok/openmetadatasync/batch/observability/BatchLifecycleLoggingListenerTest.java \
  docs/superpowers/specs/2026-08-08-human-readable-batch-logging-design.md \
  docs/superpowers/plans/2026-08-08-human-readable-batch-logging.md
git diff --cached --check
git commit -m "feat: add human-readable batch lifecycle logs"
git push origin develop
git ls-remote --heads origin develop
```

Expected: local HEAD, `origin/develop`, and remote `refs/heads/develop` resolve to the same new SHA.

- [ ] **Step 5: Run the Jenkins benchmark gate**

Run the manual `Jenkinsfile.benchmark` Item on the verified SHA, beginning with `BENCHMARK_GATE=PREFLIGHT`. Confirm `BATCH_JOB_START [배치 시작]`, Korean Step counters, `BATCH_JOB_END [배치 종료]`, correct nonzero processing totals, archived evidence, and the intended Jenkins result. This is a runtime gate and is not replaced by local tests.
