# Deterministic Heap Qualification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure retained-heap growth reproducibly, report Batch/data processing separately from scalability qualifications, and map qualification-only misses to Jenkins `UNSTABLE` while keeping `MAIN` blocked.

**Architecture:** `BenchmarkMetrics` owns the 64-sample/two-window trend calculation, `BenchmarkEvidence` owns schema-v2 processing and qualification verdicts, and Jenkins owns operator-visible result mapping under a fixed benchmark JVM heap. The existing Batch job, checkpoint, DB schema, artifact allowlist, and Crossref pipeline stay unchanged.

**Tech Stack:** Java 21, Spring Batch 6, Spring Boot 4, Jackson, JUnit 5, AssertJ, Jenkins Declarative Pipeline, Gradle.

---

### Task 1: Measure retained heap across stable windows

**Files:**
- Create: `src/test/java/com/heojungseok/openmetadatasync/batch/benchmark/BenchmarkMetricsTest.java`
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/benchmark/BenchmarkMetrics.java`

- [ ] **Step 1: Write the failing heap-trend unit test**

Create `BenchmarkMetricsTest` with four explicit cases:

```java
package com.heojungseok.openmetadatasync.batch.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkMetricsTest {

    private static final long MIB = 1024L * 1024;

    @Test
    void comparesEarlyAndLateRetainedFloorsAcrossSixtyFourSamples() {
        BenchmarkMetrics.HeapTrend bounded = BenchmarkMetrics.heapTrend(64, samples(50, 55));
        BenchmarkMetrics.HeapTrend growing = BenchmarkMetrics.heapTrend(64, samples(50, 60));
        BenchmarkMetrics.HeapTrend shrinking = BenchmarkMetrics.heapTrend(64, samples(55, 45));
        BenchmarkMetrics.HeapTrend insufficient = BenchmarkMetrics.heapTrend(63, samples(50, 50));

        assertThat(bounded).isEqualTo(new BenchmarkMetrics.HeapTrend(
                50 * MIB, 55 * MIB, 5 * MIB, 8 * MIB, true
        ));
        assertThat(growing.plateau()).isFalse();
        assertThat(growing.retainedGrowthBytes()).isEqualTo(10 * MIB);
        assertThat(shrinking.plateau()).isTrue();
        assertThat(shrinking.retainedGrowthBytes()).isEqualTo(-10 * MIB);
        assertThat(insufficient.plateau()).isFalse();
    }

    private static List<Long> samples(long firstFloorMib, long lastFloorMib) {
        List<Long> values = new ArrayList<>(Collections.nCopies(64, 80 * MIB));
        values.set(0, firstFloorMib * MIB);
        values.set(48, lastFloorMib * MIB);
        return values;
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew test --tests com.heojungseok.openmetadatasync.batch.benchmark.BenchmarkMetricsTest
```

Expected: compilation FAIL because `HeapTrend` and `heapTrend` do not exist.

- [ ] **Step 3: Implement the minimal window calculation**

In `BenchmarkMetrics`:

```java
private static final int TAIL_SAMPLES = 64;
private static final int HEAP_WINDOW = 16;
private static final long MIN_ALLOWED_GROWTH = 8L * 1024 * 1024;

static HeapTrend heapTrend(int samples, List<Long> tail) {
    if (samples < TAIL_SAMPLES || tail.size() < TAIL_SAMPLES) {
        return new HeapTrend(0, 0, 0, 0, false);
    }
    int start = tail.size() - TAIL_SAMPLES;
    long firstFloor = tail.subList(start, start + HEAP_WINDOW).stream()
            .mapToLong(Long::longValue).min().orElseThrow();
    long lastFloor = tail.subList(tail.size() - HEAP_WINDOW, tail.size()).stream()
            .mapToLong(Long::longValue).min().orElseThrow();
    long growth = lastFloor - firstFloor;
    long allowed = Math.max(MIN_ALLOWED_GROWTH, firstFloor / 10);
    return new HeapTrend(firstFloor, lastFloor, growth, allowed, growth <= allowed);
}

public record HeapTrend(
        long firstWindowFloorBytes,
        long lastWindowFloorBytes,
        long retainedGrowthBytes,
        long allowedGrowthBytes,
        boolean plateau
) {
}
```

Retain at most 64 values in `afterWrite`. In `afterStep`, compute one `HeapTrend` and store its five values under `heapFirstWindowFloor`, `heapLastWindowFloor`, `heapRetainedGrowth`, `heapAllowedGrowth`, and `heapPlateau`.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Step 2 command again. Expected: PASS.

### Task 2: Publish schema-v2 processing and qualification evidence

**Files:**
- Modify: `src/test/java/com/heojungseok/openmetadatasync/batch/benchmark/BenchmarkEvidenceTest.java`
- Modify: `src/test/java/com/heojungseok/openmetadatasync/batch/job/DataPlaneBenchmarkJobConfigTest.java`
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/job/DataPlaneBenchmarkJobConfig.java`
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/benchmark/BenchmarkMetrics.java`
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/benchmark/JpaBenchmarkEvidenceCollector.java`
- Modify: `src/main/java/com/heojungseok/openmetadatasync/batch/benchmark/BenchmarkEvidence.java`

- [ ] **Step 1: Change evidence tests first**

Update the evidence fixture to schema `v2` and construct heap evidence as:

```java
new BenchmarkEvidence.Heap(
        50, 80, 64,
        50, heapPlateau ? 55 : 60,
        heapPlateau ? 5 : 10, 8,
        heapPlateau
)
```

Require JSON and Markdown to contain:

```java
assertThat(json).contains(
        "\"schemaVersion\" : \"v2\"",
        "\"firstWindowFloorBytes\"",
        "\"lastWindowFloorBytes\"",
        "\"retainedGrowthBytes\"",
        "\"allowedGrowthBytes\""
);
assertThat(markdown).contains(
        "| Processing result | PASS |",
        "| Restart qualification | PASS |",
        "| Heap retention qualification | PASS |",
        "| Persistence qualification | PASS |",
        "| Preflight qualification | PASS |"
);
```

Add a separation test:

```java
@Test
void completedProcessingRemainsPassWhenHeapQualificationIsNotMet() throws Exception {
    BenchmarkEvidence evidence = evidence(100_000, true, false, 1);

    evidence.requireProcessingResult();
    assertThatThrownBy(evidence::requirePreflightQualification)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("heap");

    String markdown = Files.readString(evidence.write(output).markdown());
    assertThat(markdown).contains(
            "| Processing result | PASS |",
            "| Heap retention qualification | FAIL |",
            "| Preflight qualification | FAIL |"
    );
}
```

Rename existing `requirePreflight()` calls to `requirePreflightQualification()`. Add a test that rewrites `schemaVersion` to `v1` and verifies `requireMillionGate` rejects it with `profile` or `schema` in the message.

Delete the obsolete `retainedHeapFloorPassesGcSawtoothButRejectsGrowthAndInsufficientSamples` test from `DataPlaneBenchmarkJobConfigTest`; Task 1 now owns the pure metric contract.

- [ ] **Step 2: Run the evidence tests and verify RED**

Run:

```bash
./gradlew test --tests com.heojungseok.openmetadatasync.batch.benchmark.BenchmarkEvidenceTest
```

Expected: compilation FAIL because the v2 heap fields and separated methods do not exist.

- [ ] **Step 3: Wire the metric snapshot and v2 collector**

Change `BenchmarkMetrics.Snapshot` to hold `HeapTrend heapTrend` instead of a single heap boolean. In `DataPlaneBenchmarkJobConfig`, reconstruct `HeapTrend` from the five execution-context keys and pass it into the snapshot. Preserve existing failed/successful execution aggregation for JDBC/DML/query/time counters.

In `JpaBenchmarkEvidenceCollector`:

```java
BenchmarkMetrics.HeapTrend trend = metrics.heapTrend();
return new BenchmarkEvidence(
        "v2", syncContractHash, scenario, rowCount, seed, generatorVersion, chunkSize,
        // existing fields
        new BenchmarkEvidence.Heap(
                metrics.baselineHeap(), metrics.peakHeap(), metrics.heapSamples(),
                trend.firstWindowFloorBytes(), trend.lastWindowFloorBytes(),
                trend.retainedGrowthBytes(), trend.allowedGrowthBytes(), trend.plateau()
        ),
        // existing fields
);
```

- [ ] **Step 4: Separate processing from qualifications in `BenchmarkEvidence`**

Replace the combined method with:

```java
public void requireProcessingResult() {
    requireScenarioSemantics();
    if (!"COMPLETED".equals(batchStatus) || !"COMPLETED".equals(exitStatus)
            || outcomes.total() != rowCount || !checksums.staging().equals(checksums.target())) {
        throw new IllegalStateException("Benchmark processing reconciliation or checksum failed");
    }
    if (rows.staging() != rowCount || rows.target() != rowCount || rows.distinctDoi() != rowCount) {
        throw new IllegalStateException("Benchmark processing row integrity failed");
    }
}

public void requirePreflightQualification() {
    if (!"v2".equals(schemaVersion)) {
        throw new IllegalStateException("Preflight evidence schema must be v2");
    }
    requireProcessingResult();
    if (rowCount != 100_000) {
        throw new IllegalStateException("Preflight requires exactly 100000 rows");
    }
    if (!restart.attempted() || !restart.passed()) {
        throw new IllegalStateException("Preflight restart qualification failed");
    }
    if (!heap.plateau()) {
        throw new IllegalStateException("Preflight heap retention qualification failed");
    }
    if (persistence.jdbcBatches() <= 0) {
        throw new IllegalStateException("Preflight persistence qualification failed");
    }
}
```

Have `requireMillionGate` call `requirePreflightQualification()` and require schema `v2`. Render the five exact Markdown verdict lines by evaluating processing, restart, heap retention, persistence, and combined preflight independently. Keep secret-free atomic writes and exact evidence paths unchanged.

- [ ] **Step 5: Run focused Java tests and verify GREEN**

Run:

```bash
./gradlew test \
  --tests com.heojungseok.openmetadatasync.batch.benchmark.BenchmarkMetricsTest \
  --tests com.heojungseok.openmetadatasync.batch.benchmark.BenchmarkEvidenceTest \
  --tests com.heojungseok.openmetadatasync.batch.job.DataPlaneBenchmarkJobConfigTest
```

Expected: PASS.

### Task 3: Separate Jenkins processing failure from qualification misses

**Files:**
- Modify: `src/test/java/com/heojungseok/openmetadatasync/jenkins/JenkinsPipelineContractTest.java`
- Modify: `Jenkinsfile.benchmark`

- [ ] **Step 1: Write the failing Jenkins contract assertions**

Replace the old `Preflight gate`/`BENCHMARK_GATE_FAILURE` expectations with assertions for:

```java
.contains("java -Xms128m -Xmx256m -jar")
.contains("grep -Fqx '| Processing result | PASS |' ${currentMarkdown}")
.contains("grep -Fqx '| Preflight qualification | PASS |' ${currentMarkdown}")
.contains("grep -Fqx '| Restart qualification | PASS |' ${currentMarkdown}")
.contains("grep -Fqx '| Heap retention qualification | PASS |' ${currentMarkdown}")
.contains("grep -Fqx '| Persistence qualification | PASS |' ${currentMarkdown}")
.contains("BENCHMARK_PROCESSING_FAILURE [벤치마크 처리 검증 실패]")
.contains("BENCHMARK_QUALIFICATION_NOT_MET [벤치마크 자격 미충족]")
.contains("currentBuild.result = 'UNSTABLE'")
.doesNotContain("BENCHMARK_GATE_FAILURE [벤치마크 판정 실패]")
```

Require `Jenkinsfile.crossref` not to contain `-Xmx256m`, `BENCHMARK_PROCESSING_FAILURE`, or `BENCHMARK_QUALIFICATION_NOT_MET`.

- [ ] **Step 2: Run the Jenkins contract and verify RED**

Run:

```bash
./gradlew test --tests com.heojungseok.openmetadatasync.jenkins.JenkinsPipelineContractTest
```

Expected: FAIL because the benchmark command and result mapping still use the old contract.

- [ ] **Step 3: Implement the Jenkins mapping**

Change only the benchmark `java` command to:

```groovy
java -Xms128m -Xmx256m -jar build/libs/open-metadata-sync-0.0.1-SNAPSHOT.jar \
```

After file validation, evaluate the exact current Markdown lines with short-circuiting so missing files are never grepped. Apply this decision order:

```groovy
if (!outcomeValid || !evidenceFilesValid || !processingPassed) {
    currentBuild.result = 'FAILURE'
} else if (params.BENCHMARK_GATE == 'PREFLIGHT' && !preflightQualified) {
    currentBuild.result = 'UNSTABLE'
} else if (status == 0) {
    currentBuild.result = 'SUCCESS'
} else if (status == 2) {
    currentBuild.result = 'UNSTABLE'
} else if (status == 3) {
    currentBuild.result = 'NOT_BUILT'
} else {
    currentBuild.result = 'FAILURE'
}
```

For a processing miss, echo only fixed identifiers and the allowlisted path. For a qualification miss, grep the three component qualification lines and emit:

```text
BENCHMARK_QUALIFICATION_NOT_MET [벤치마크 자격 미충족] gate=PREFLIGHT processing=PASS restart=PASS heapRetention=FAIL persistence=PASS evidence=benchmark-evidence/benchmark-100000-initial.md
```

Keep artifact admission on `outcomeValid && successLike && evidenceFilesValid`, so rejected evidence remains diagnosable.

- [ ] **Step 4: Run the Jenkins contract and verify GREEN**

Run the Step 2 command again. Expected: PASS.

### Task 4: Update the operator contract

**Files:**
- Modify: `README.md`
- Modify: `docs/evidence/README.md`

- [ ] **Step 1: Document the separated meanings**

Document this mapping exactly:

| Evidence/result | Meaning |
|---|---|
| Processing result PASS | Batch/data processing completed correctly |
| Qualification miss | Processing succeeded, but the profile is not ready for MAIN |
| Jenkins FAILURE | Application/outcome/evidence/processing failure |
| Jenkins UNSTABLE | Processing PASS, qualification not met |
| Jenkins SUCCESS | Processing and all preflight qualifications PASS |

State that heap retention is a scalability qualification for the synthetic data plane only, not a GC-health check and not proof of future external-API collection memory behavior. Record schema v2 and the fixed 128/256 MiB Jenkins benchmark heap.

- [ ] **Step 2: Run all focused contracts**

Run:

```bash
./gradlew test \
  --tests com.heojungseok.openmetadatasync.batch.benchmark.BenchmarkMetricsTest \
  --tests com.heojungseok.openmetadatasync.batch.benchmark.BenchmarkEvidenceTest \
  --tests com.heojungseok.openmetadatasync.batch.job.DataPlaneBenchmarkJobConfigTest \
  --tests com.heojungseok.openmetadatasync.jenkins.JenkinsPipelineContractTest
```

Expected: PASS.

### Task 5: Verify, review, commit, and publish develop

**Files:**
- Verify all files changed in Tasks 1–4 plus this plan and the approved design.

- [ ] **Step 1: Run clean verification**

Run:

```bash
./gradlew clean test
git diff --check
```

Expected: Gradle exit `0`, all tests pass, and no whitespace errors.

- [ ] **Step 2: Request one independent benchmark/observability review**

Provide the fixed diff and local verification output. Require review of heap math, schema-v2 invalidation, processing/qualification separation, Jenkins short-circuit/result mapping, secret safety, exact artifacts, and proof boundaries. Resolve all Blocker/High and re-review changed owners.

- [ ] **Step 3: Commit the implementation scope**

Stage only the approved Java/tests/Jenkins/docs/plan files, verify `git diff --cached --check` and the cached names, then commit:

```text
fix: separate benchmark processing and qualification
```

- [ ] **Step 4: Push and verify exact remote SHA**

Push `develop`, then confirm `git rev-parse HEAD`, `git rev-parse origin/develop`, and `git ls-remote --heads origin develop` are identical. Do not modify `main`.

### Task 6: Re-prove the Jenkins runtime gate

**Files:**
- Runtime artifacts: `benchmark-evidence/benchmark-100000-initial.{json,md}`
- Runtime artifacts: `benchmark-evidence/benchmark-100000-no-op.{json,md}`

- [ ] **Step 1: Run a qualification-only miss on the pushed SHA**

Use a new seed/request without failure injection. Expected: application code `0`, `Processing result PASS`, at least one qualification FAIL, explicit qualification event, Jenkins `UNSTABLE`, and exact evidence artifacts. This proves separation without claiming qualification success.

- [ ] **Step 2: Run the initial injected/restart pair with a fresh seed**

First execution: `FAIL_FIRST_EXECUTION=true`, expected Jenkins `FAILURE` after one committed chunk. Second execution: identical identifying parameters and `FAIL_FIRST_EXECUTION=false`, expected 99 remaining commits, v2 processing and all qualifications PASS, max heap 268,435,456 bytes, Jenkins `SUCCESS`.

- [ ] **Step 3: Run the no-op injected/restart pair**

Use the same seed/tuning and a new no-op request ID. Expected first `FAILURE`, restart `SUCCESS`, all v2 verdicts PASS. Only then are both prerequisites ready for a separately approved `MAIN` run.
