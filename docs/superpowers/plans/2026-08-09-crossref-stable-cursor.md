# Crossref Stable Cursor Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow Crossref's stable cursor token to paginate beyond the first 1,000 items without weakening existing duplicate-page and request-bound safety checks.

**Architecture:** Keep cursor handling inside `CrossrefCollector`. Remove only the assumption that cursor text must change; retain the non-blank cursor check, page caps and zero-new-staging-row guard.

**Tech Stack:** Java 21, Spring Batch, JUnit 5, AssertJ, Gradle

---

### Task 1: Capture stable cursor pagination as a regression test

**Files:**
- Modify: `src/test/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollectorTest.java`

- [x] **Step 1: Add the failing regression test**

```java
@Test
void stableCursorTokenCanAdvanceToMaxItems() {
	FakeClient client = new FakeClient(
			response(1_000, "stable-cursor", 9_000, 0),
			response(1_000, "stable-cursor", 9_000, 1_000)
	);

	CrossrefCollector.Result result = collector(client, new MemoryStore(), new ArrayList<>())
			.collect(request(2_000));

	assertThat(result.expectedCount()).isEqualTo(2_000);
	assertThat(result.pagesFetched()).isEqualTo(2);
	assertThat(result.stopReason()).isEqualTo(CrossrefCollector.StopReason.MAX_ITEMS);
	assertThat(client.cursors).containsExactly("*", "stable-cursor");
}
```

- [x] **Step 2: Run the new test and verify RED**

Run:

```bash
./gradlew test --tests 'com.heojungseok.openmetadatasync.batch.collect.CrossrefCollectorTest.stableCursorTokenCanAdvanceToMaxItems'
```

Expected: FAIL because the collector rejects the unchanged second-page cursor and reaches its safety bound instead of returning 2,000 items.

- [x] **Step 3: Keep the page-cap assertion focused**

Rename `repeatedCursorAndPageSafetyCapFailWithEvidence` to `pageSafetyCapFailsWithEvidence` and remove only its repeated-cursor setup/assertion. Keep the existing capped request and evidence assertions unchanged.

### Task 2: Remove the invalid cursor-text progress assumption

**Files:**
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollector.java`
- Test: `src/test/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollectorTest.java`

- [x] **Step 1: Make the minimal implementation change**

Delete the unused counter:

```java
int noProgress = 0;
```

Delete the cursor-equality rejection and reset:

```java
if (!shortPage && Objects.equals(cursor, message.nextCursor())) {
	if (++noProgress >= request.consecutiveNoProgressLimit()) {
		throw safety("Crossref cursor made no progress", request, pagesFetched,
				reportedTotalResults, windowEvidence);
	}
	continue;
}
noProgress = 0;
```

Do not change the blank-cursor, page-bound or zero-new-row guards.

- [x] **Step 2: Run focused collector tests and verify GREEN**

Run:

```bash
./gradlew test --tests 'com.heojungseok.openmetadatasync.batch.collect.CrossrefCollectorTest'
```

Expected: BUILD SUCCESSFUL.

### Task 3: Verify and publish the code fix

**Files:**
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollector.java`
- Modify: `src/test/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollectorTest.java`

- [x] **Step 1: Run the full suite**

```bash
./gradlew clean test --rerun-tasks
```

Expected: BUILD SUCCESSFUL with zero test failures and errors.

- [x] **Step 2: Check repository scope**

```bash
git diff --check
git status --short --branch
```

Expected: only the collector and collector test are changed; no whitespace errors.

- [x] **Step 3: Commit and push**

```bash
git add src/main/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollector.java \
  src/test/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollectorTest.java
git commit -m "fix: support Crossref stable cursors"
git push origin develop
```

Expected: local `HEAD` and `origin/develop` resolve to the same commit.

### Task 4: Re-run final Jenkins evidence

**Files:**
- Verify: Jenkins `open-metadata-sync/crossref`
- Verify: Jenkins build archive and local Jenkins build records

- [x] **Step 1: Launch the actual BACKFILL**

The first fixed run reused the failed JobInstance identity and completed as Jenkins #5 at
`180e050`. After the repeated-payload review fix, that JobInstance was already complete, so
the final-SHA regression used a new immutable request identity instead of bypassing Spring
Batch's completed-instance contract:

```text
REQUEST_ID=jenkins-crossref-100k-20260809-02
MODE=BACKFILL
CREATED_FROM=2026-08-01
CREATED_UNTIL=2026-08-08
MAX_ITEMS=100000
CHUNK_SIZE=1000
HIBERNATE_BATCH_SIZE=1000
```

- [x] **Step 2: Verify Jenkins and database evidence**

Confirm Jenkins checks out the pushed fix SHA, the job completes successfully, 100,000 rows are collected/accounted for and final integrity verification passes. Archive the resulting Jenkins evidence before final Git integration.

### Task 5: Close the repeated-payload review finding

**Files:**
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollector.java`
- Modify: `src/test/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollectorTest.java`

- [x] **Step 1: Make stable-cursor pages distinct in the regression test**

Use DOI offsets `0` and `1_000` so the stable-cursor success test proves cursor-token reuse with different payloads.

- [x] **Step 2: Add a RED test for identical consecutive full pages**

Return the same 1,000-item payload three times with the same cursor. Expect `CollectionSafetyException` after three fetches and only the first 1,000 rows persisted.

- [x] **Step 3: Add the bounded payload guard**

Retain only the previous full page. Validate a full page's non-blank cursor before payload comparison. Skip one identical consecutive payload and fail when the existing `consecutiveNoProgressLimit` is reached. Count duplicate fetches in the global safety cap and evidence, but count only distinct payload pages against the total-results-derived page bound. Do not add a migration, configuration or unbounded fingerprint set.

- [x] **Step 4: Cover review edge cases with RED/GREEN tests**

Verify a repeated page with a blank cursor fails immediately without another fetch, and verify `full A → duplicate A → short B` completes 1,500 rows without persisting A twice.

- [x] **Step 5: Re-run focused and full verification**

Run the collector test class and `./gradlew clean test --rerun-tasks`, then repeat the Git, Jenkins 100,000-item and backup gates on the new SHA.

## Final reconciliation — 2026-08-09

- Final implementation: `develop@512dc7311e68dbe083fa8746d73571f83eb9a5e6`, local tracking branch and remote `develop` exact match.
- Verification: `./gradlew clean test --rerun-tasks` completed with 128 tests, 0 failures, 0 errors and 0 skipped. Two independent data/code reviews returned `PASS` after the blank-cursor ordering and derived-page-bound fixes.
- Jenkins: Crossref #6 checked out `512dc7311e68dbe083fa8746d73571f83eb9a5e6`; request `jenkins-crossref-100k-20260809-02` completed in 7m50s. Collection was 100,000 rows, sync was 100 commits with 0 rollbacks and verify completed.
- Database reconciliation: expected/staging/distinct DOI/accounted were all 100,000; 100 chunk results accounted for inserted 2,385, no-op 97,572, index-advanced 39 and updated 4; conflict, validation error and `sync_error` were 0.
- Durable local evidence: Jenkins build record `/Users/heojungseok/.jenkins/jobs/open-metadata-sync/jobs/crossref/builds/6`; final test-result archive is recorded with the final external backup below.
- External recovery evidence: `/Volumes/sd-128/open-metadata-sync/2026-08-09-final/database-backups/open_metadata_after_final_crossref_v2.sql.gz`, `jenkins-evidence/jenkins-crossref-build-6.tar.gz`, `jenkins-evidence/final-tests-512dc73.tar.gz` and `checksums/SHA256SUMS-after-final-v2.txt`. SHA-256, gzip and tar checks passed; the test archive contains the 128-test XML/HTML report, and DB restore verification reproduced 5 migrations, completed 100,000-row execution, 100 chunks/accounted 100,000 and 0 errors.
- Evidence labels: Crossref #5 at `180e050` is provisional historical evidence superseded by #6. Benchmark #14/#15 at `8d6dd852` remain Milestone 2 one-million-row performance evidence, while PREFLIGHT #19/#21 at `7350aa1` remain operational restart evidence; neither is mislabeled as final-SHA Crossref evidence.
- Preservation: benchmark/actual schemas, Docker volume, historical Jenkins builds, branches and worktrees remain intact pending separate cleanup approval.
