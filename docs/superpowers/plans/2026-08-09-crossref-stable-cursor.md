# Crossref Stable Cursor Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow Crossref's stable cursor token to paginate beyond the first 1,000 items without weakening existing duplicate-page and request-bound safety checks.

**Architecture:** Keep cursor handling inside `CrossrefCollector`. Remove only the assumption that cursor text must change; retain the non-blank cursor check, page caps and zero-new-staging-row guard.

**Tech Stack:** Java 21, Spring Batch, JUnit 5, AssertJ, Gradle

---

### Task 1: Capture stable cursor pagination as a regression test

**Files:**
- Modify: `src/test/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollectorTest.java`

- [ ] **Step 1: Add the failing regression test**

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

- [ ] **Step 2: Run the new test and verify RED**

Run:

```bash
./gradlew test --tests 'com.heojungseok.openmetadatasync.batch.collect.CrossrefCollectorTest.stableCursorTokenCanAdvanceToMaxItems'
```

Expected: FAIL because the collector rejects the unchanged second-page cursor and reaches its safety bound instead of returning 2,000 items.

- [ ] **Step 3: Keep the page-cap assertion focused**

Rename `repeatedCursorAndPageSafetyCapFailWithEvidence` to `pageSafetyCapFailsWithEvidence` and remove only its repeated-cursor setup/assertion. Keep the existing capped request and evidence assertions unchanged.

### Task 2: Remove the invalid cursor-text progress assumption

**Files:**
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollector.java`
- Test: `src/test/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollectorTest.java`

- [ ] **Step 1: Make the minimal implementation change**

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

- [ ] **Step 2: Run focused collector tests and verify GREEN**

Run:

```bash
./gradlew test --tests 'com.heojungseok.openmetadatasync.batch.collect.CrossrefCollectorTest'
```

Expected: BUILD SUCCESSFUL.

### Task 3: Verify and publish the code fix

**Files:**
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollector.java`
- Modify: `src/test/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollectorTest.java`

- [ ] **Step 1: Run the full suite**

```bash
./gradlew clean test --rerun-tasks
```

Expected: BUILD SUCCESSFUL with zero test failures and errors.

- [ ] **Step 2: Check repository scope**

```bash
git diff --check
git status --short --branch
```

Expected: only the collector and collector test are changed; no whitespace errors.

- [ ] **Step 3: Commit and push**

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

- [ ] **Step 1: Launch the actual BACKFILL**

Use the same failed JobInstance identity so Spring Batch restarts safely:

```text
REQUEST_ID=jenkins-crossref-100k-20260809-01
MODE=BACKFILL
CREATED_FROM=2026-08-01
CREATED_UNTIL=2026-08-08
MAX_ITEMS=100000
CHUNK_SIZE=1000
HIBERNATE_BATCH_SIZE=1000
```

- [ ] **Step 2: Verify Jenkins and database evidence**

Confirm Jenkins checks out the pushed fix SHA, the job completes successfully, 100,000 rows are collected/accounted for and final integrity verification passes. Archive the resulting Jenkins evidence before final Git integration.

### Task 5: Close the repeated-payload review finding

**Files:**
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollector.java`
- Modify: `src/test/java/com/heojungseok/openmetadatasync/batch/collect/CrossrefCollectorTest.java`

- [ ] **Step 1: Make stable-cursor pages distinct in the regression test**

Use DOI offsets `0` and `1_000` so the stable-cursor success test proves cursor-token reuse with different payloads.

- [ ] **Step 2: Add a RED test for identical consecutive full pages**

Return the same 1,000-item payload three times with the same cursor. Expect `CollectionSafetyException` after three fetches and only the first 1,000 rows persisted.

- [ ] **Step 3: Add the bounded payload guard**

Retain only the previous full page. Validate a full page's non-blank cursor before payload comparison. Skip one identical consecutive payload and fail when the existing `consecutiveNoProgressLimit` is reached. Count duplicate fetches in the global safety cap and evidence, but count only distinct payload pages against the total-results-derived page bound. Do not add a migration, configuration or unbounded fingerprint set.

- [ ] **Step 4: Cover review edge cases with RED/GREEN tests**

Verify a repeated page with a blank cursor fails immediately without another fetch, and verify `full A → duplicate A → short B` completes 1,500 rows without persisting A twice.

- [ ] **Step 5: Re-run focused and full verification**

Run the collector test class and `./gradlew clean test --rerun-tasks`, then repeat the Git, Jenkins 100,000-item and backup gates on the new SHA.
