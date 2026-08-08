# Deterministic heap qualification design

## 1. Purpose and scope

[목표 상태] Replace the GC-timing-sensitive 100k heap verdict with a reproducible retained-growth comparison, separate Batch/data completion from scalability qualifications, and run the Jenkins benchmark under a fixed JVM heap envelope before allowing a 1M launch.

The change is limited to benchmark metrics/evidence Java code, focused tests, `Jenkinsfile.benchmark`, and operator documentation. Crossref processing, data-plane Reader/Writer behavior, DB schema, Jenkins plugins, Folder settings, and `main` remain unchanged.

## 2. Current confirmed facts

[현재 사실]

- `develop@81cad2c3f911688b1eb5bc173dfb6572c837d1ed` is clean and equals `origin/develop`.
- The injected initial run committed one 1,000-row chunk and rolled back the second chunk.
- The restart created a new JobExecution, skipped completed tasklets, and processed only the remaining 99,000 rows in 99 commits.
- The resulting evidence recorded correct reconciliation, checksums, 100,000 inserts, JDBC batching, and `restart.attempted=true`, `restart.passed=true`.
- The only failed condition was `heap.plateau=false`; baseline was 44,875,720 bytes, peak was 89,502,480 bytes, samples were 99, and the JVM max heap was 4,294,967,296 bytes.
- The current algorithm keeps only the last 16 samples and compares their minimum with the pre-sync baseline using an 8 MiB-or-10% tolerance. The evidence does not preserve the compared tail floor.
- Git history contains an unmerged alternative (`c9e3416`) that compares early and late windows within 64 tail samples. It is design evidence, not code to cherry-pick blindly.

## 3. Unverified assumptions

[가정]

- A 256 MiB maximum heap is sufficient for the bounded benchmark because the observed 100k peak is about 90 MiB and preload/sync are page/chunk bounded. The actual Jenkins 100k restart pairs will verify this assumption.
- A 64-sample tail with 16-sample windows is long enough to observe the workload trend for the fixed 100k/1,000-chunk profile, which produces about 100 samples.
- If the assumption is wrong, Jenkins will report the completed processing as `UNSTABLE` with diagnostic floors/growth and will not qualify the profile for `MAIN`.

## 4. Target state

[목표 상태]

1. `BenchmarkMetrics` retains the most recent 64 post-write heap samples.
2. It compares the minimum used heap in the first 16 samples of that retained window with the minimum in the last 16 samples.
3. `retainedGrowthBytes = lastWindowFloorBytes - firstWindowFloorBytes`.
4. `allowedGrowthBytes = max(8 MiB, firstWindowFloorBytes / 10)`.
5. Heap retention qualification passes only when at least 64 samples exist and retained growth does not exceed the allowance.
6. Evidence schema `v2` records baseline, peak, sample count, both window floors, retained growth, allowed growth, and the heap-retention verdict.
7. `Processing result` covers scenario semantics, completed Batch/exit status, outcome reconciliation, checksums, and row integrity. It answers whether the current data processing succeeded and does not include scalability qualifications.
8. `Restart qualification` covers restart attempted/passed, `Heap retention qualification` covers the v2 trend contract, and `Persistence qualification` covers positive JDBC batch evidence.
9. `Preflight qualification` requires exactly 100,000 rows plus PASS for processing, restart, heap retention, and persistence. It is readiness evidence for `MAIN`, not the current Batch completion result.
10. Markdown exposes all five results independently.
11. Jenkins maps a missing/invalid outcome, application failure, missing evidence, or `Processing result | FAIL` to `FAILURE`.
12. Jenkins maps `Processing result | PASS` with `Preflight qualification | FAIL` to `UNSTABLE` and emits one `BENCHMARK_QUALIFICATION_NOT_MET [벤치마크 자격 미충족]` record containing the restart, heap-retention, and persistence component verdicts.
13. Jenkins maps application status `0` plus processing/qualification PASS to `SUCCESS`; status `2` remains `UNSTABLE`, and status `3` remains `NOT_BUILT`.
14. `MAIN` validates current processing evidence, while the application continues to require both stored 100k v2 preflight qualifications before launching 1M work.
15. The Jenkins benchmark process runs with `-Xms128m -Xmx256m`; Crossref is not capped by this change.
16. For a restarted profile, the heap trend is taken from the current successful `sync` StepExecution; DML/query/batch totals continue to aggregate the failed and successful executions as they do today.

## 5. Existing design assumptions

[현재 사실]

- Successful Batch/data processing is a separate fact from 1M readiness.
- Both 100k scenarios must prove data semantics, reconciliation, restart durability, bounded heap behavior, and JDBC batching before `MAIN`.
- Evidence paths are fixed by row count and scenario, and the application owns evidence computation while Jenkins owns admission.

## 6. Constraints

[제약]

- Do not call `System.gc()`; it is only a request and would couple the benchmark to an artificial pause.
- Do not tune a threshold merely to make the observed run pass.
- Do not weaken restart, row integrity, checksum, or JDBC batch gates.
- Do not accept old schema `v1` evidence for a new 1M launch.
- Local tests cannot prove macOS Jenkins GC behavior; exact-SHA Jenkins execution remains mandatory.

## 7. Broken assumptions

[현재 사실]

The previous design assumed the last 16 samples would contain a GC-low value comparable to the pre-sync baseline. With a roughly 4 GiB ergonomic max heap and a roughly 90 MiB workload peak, collection timing is not controlled, so a bounded workload can be rejected without enough diagnostic data to distinguish retained growth from a late collection.

## 8. Impact analysis

[목표 상태]

- **Java contract:** benchmark heap metric and evidence fields change; schema becomes `v2`; processing and qualification checks become distinct methods/verdicts.
- **Jenkins:** benchmark JVM heap flags become fixed in source control and qualification-only rejection maps to `UNSTABLE` instead of generic `FAILURE`.
- **Artifacts:** the same exact JSON/Markdown paths are archived, now containing v2 diagnostics.
- **1M readiness:** existing v1 initial/no-op files are invalid and both v2 profiles must be regenerated with matching seed/tuning.
- **Tests/docs:** metric math, schema rejection, Markdown lines, Jenkins flags, and operator process are updated.
- **No impact:** database tables/data format, public API, Crossref job, credentials, locking, outcome codes, branch topology, or deployment topology.

## 9. Alternatives

### Selected: Java trend measurement plus fixed Jenkins heap

This measures the intended property—retained growth across workload progress—while making the JVM envelope reproducible. It preserves fail-closed `MAIN` admission without misreporting successful data processing as an application failure.

### Rejected: Java algorithm only

It improves the comparison but leaves max heap and collection pressure dependent on the Jenkins host, so runs on different agents can still diverge.

### Rejected: Jenkins heap flags only

It may trigger GC more often but retains the flawed comparison against a single pre-sync baseline and does not expose the values behind a failure.

### Rejected: explicit `System.gc()`

It distorts timing, is not guaranteed to collect, and tests an artificial stop-the-world request rather than normal bounded processing.

## 10. Expected benefits

[목표 상태]

- A heap-retention failure states how much the retained floor grew and what allowance was applied.
- Batch/data success is visible independently from restart, heap-retention, and persistence qualifications.
- Operators see a yellow `UNSTABLE` qualification result instead of a red processing `FAILURE` when only readiness evidence is missing.
- The 100k qualification is comparable across repeated Jenkins runs.
- A 1M run cannot reuse evidence produced under the superseded v1 heap contract.

## 11. Risks and failure scenarios

[가정]

- `-Xmx256m` may be too small: the application fails clearly with OOM and no PASS evidence; raise it only from measured evidence and a new approval.
- Fewer than 64 samples: heap-retention qualification fails and Jenkins is `UNSTABLE`; `MAIN` remains blocked. The approved profile uses 100k rows and chunk size 1,000, yielding about 100 samples.
- No meaningful floor stabilization: retained growth exceeds allowance and heap-retention qualification fails with both floors recorded.
- Old evidence remains in the workspace after a failed attempt: application status `1` prevents Jenkins from admitting or archiving it as current success evidence; a successful run atomically replaces the exact files.
- A false accept could allow a risky 1M launch, so one independent benchmark/observability review is required after implementation.

## 12. Readiness and resilience

[목표 상태]

- **Readiness:** Java 21 tool, Folder DB properties, credential, shared lock, exact v2 preflight files, seed/tuning match, and 256 MiB max heap.
- **Resilience:** the existing Batch checkpoint/restart behavior is unchanged; a failed first execution resumes from the last committed key. Evidence writes remain atomic.

## 13. Completion conditions and verification

[검증 예정]

- Focused metric tests record RED then GREEN for bounded sawtooth, retained growth, negative growth, and insufficient samples.
- Evidence tests prove v2 JSON/Markdown diagnostics, distinct processing/qualification verdicts, and v1 rejection at the million gate.
- Jenkins contract tests prove `-Xms128m -Xmx256m` applies only to the benchmark application command; processing failure maps to `FAILURE`, qualification-only failure maps to `UNSTABLE`, and exact qualification PASS maps to `SUCCESS` for status `0`.
- `./gradlew clean test` passes on the final diff.
- `git diff --check` passes and one independent reviewer reports no unresolved Blocker or High.
- The final commit is pushed to `develop`; local, tracking, and remote SHAs match.
- On the exact pushed SHA, the initial injected run fails after one committed chunk and the restart processes 99 remaining chunks, then evidence reports:
  - `Processing result | PASS`
  - `Restart qualification | PASS`
  - `Heap retention qualification | PASS`
  - `Persistence qualification | PASS`
  - `Preflight qualification | PASS`
  - max heap 268,435,456 bytes
  - Jenkins `SUCCESS`
- The no-op injected/restart pair must then produce the same three PASS verdicts with the same seed/tuning before `MAIN`.

## 14. Rollback and recovery

[목표 상태]

Rollback is a Git revert of the Java/Jenkins/test/documentation commit. No DB migration or cleanup is required. v2 evidence may remain as an artifact but the reverted v1 code will reject it by schema mismatch; rerun the v1 profiles only if rollback is intentionally retained.

## 15. User decisions

[결정 완료]

The user approved the combined Java measurement plus Jenkins fixed-heap approach on 2026-08-09, then explicitly required Batch/data completion to remain separate from scalability qualifications. Qualification-only failure must be operator-visible as `UNSTABLE`, while `MAIN` remains blocked. No destructive cleanup, `main` integration, or 1M execution is authorized by this approval.

## 16. Evidence sources

[현재 사실]

- `BenchmarkMetrics.java`, `DataPlaneBenchmarkJobConfig.java`, `BenchmarkEvidence.java`, `JpaBenchmarkEvidenceCollector.java`
- `Jenkinsfile.benchmark`, `JenkinsPipelineContractTest.java`
- Jenkins evidence `benchmark-100000-initial.json` generated at 2026-08-09 00:35 KST
- Failed JobExecution 7 and restarted JobExecution 8 console logs
- commits `81cad2c`, `c82c2ef`, and unmerged alternative `c9e3416`

## Risk score and review gate

[현재 사실]

- Impact scope: 1 — benchmark Java/evidence and its Jenkins job.
- Failure impact: 1 — recoverable false accept/reject of a qualification run.
- Reversibility: 0 — source revert; no schema migration.
- Verification uncertainty: 2 — actual GC behavior requires the local Jenkins runtime.
- Total: 4, medium; no forced-high condition.

[목표 상태]

Use one independent benchmark/observability reviewer on the fixed candidate and final local evidence. Runtime Jenkins proof remains a separate environment gate and cannot be replaced by review.
