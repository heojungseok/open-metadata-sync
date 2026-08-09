# Jenkins preflight evidence gate implementation plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `BENCHMARK_GATE=PREFLIGHT` fail in Jenkins unless the current generated Markdown evidence contains the exact verdict `| Preflight gate | PASS |`.

**Architecture:** Keep evidence computation in the application and add only a Jenkins admission check at the existing outcome/evidence boundary. Preserve all application, database, Batch status, `MAIN`, artifact, and branch-integration behavior.

**Tech Stack:** Jenkins Declarative Pipeline (Groovy), POSIX `grep`, JUnit 5, AssertJ, Gradle.

---

### Task 1: Lock the Jenkins contract with a failing test

**Files:**
- Modify: `src/test/java/com/heojungseok/openmetadatasync/jenkins/JenkinsPipelineContractTest.java`

**Step 1: Add the failing benchmark assertions**

Require the benchmark pipeline to contain:

```java
.contains("grep -Fqx '| Preflight gate | PASS |' ${currentMarkdown}")
.contains("params.BENCHMARK_GATE != 'PREFLIGHT'")
.contains("BENCHMARK_GATE_FAILURE [벤치마크 판정 실패]")
.contains("reason=PREFLIGHT_NOT_PASS")
.contains("boolean evidenceValid = evidenceFilesValid && preflightPassed")
```

Also require the Crossref pipeline not to contain `Preflight gate` or `BENCHMARK_GATE_FAILURE`.

**Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew test --tests com.heojungseok.openmetadatasync.jenkins.JenkinsPipelineContractTest
```

Expected: FAIL because `Jenkinsfile.benchmark` does not yet inspect the generated preflight verdict.

### Task 2: Implement the smallest fail-closed Jenkins check

**Files:**
- Modify: `Jenkinsfile.benchmark`

**Step 1: Separate file validation from verdict validation**

Replace the single `evidenceValid` expression with:

```groovy
boolean evidenceFilesValid = !successLike || sh(returnStatus: true, script: """
    test -f ${currentJson} && test -f ${currentMarkdown}
""") == 0
boolean preflightPassed = params.BENCHMARK_GATE != 'PREFLIGHT' || !successLike || !evidenceFilesValid
        || sh(returnStatus: true, script: """
            grep -Fqx '| Preflight gate | PASS |' ${currentMarkdown}
        """) == 0
if (successLike && evidenceFilesValid && !preflightPassed) {
    echo "BENCHMARK_GATE_FAILURE [벤치마크 판정 실패] gate=PREFLIGHT evidence=${currentMarkdown} reason=PREFLIGHT_NOT_PASS"
}
boolean evidenceValid = evidenceFilesValid && preflightPassed
```

This avoids running `grep` when the application failed or the evidence files are missing, and leaves `MAIN` unchanged.

Keep artifact admission on the existing file boundary:

```groovy
if (outcomeValid && successLike && evidenceFilesValid) {
```

This preserves the exact current JSON/Markdown pair for diagnosis even when the new preflight verdict rejects the build.

**Step 2: Run the focused test and verify GREEN**

Run:

```bash
./gradlew test --tests com.heojungseok.openmetadatasync.jenkins.JenkinsPipelineContractTest
```

Expected: PASS.

### Task 3: Document the operator-visible behavior

**Files:**
- Modify: `README.md`

**Step 1: Update the Jenkins benchmark guidance**

State that:

- `PREFLIGHT` injects the fixed `benchmark-preflight` profile and 100,000 rows.
- A status `0` or `2` is admitted only when the current Markdown has the exact `Preflight gate | PASS` verdict.
- A rejected verdict produces `BENCHMARK_GATE_FAILURE [벤치마크 판정 실패]` and Jenkins `FAILURE`.
- `MAIN` continues to use the application's stored preflight validation and is not checked against a 1M current-file preflight verdict.

**Step 2: Run the focused contract test again**

Run:

```bash
./gradlew test --tests com.heojungseok.openmetadatasync.jenkins.JenkinsPipelineContractTest
```

Expected: PASS.

### Task 4: Verify, review, and publish the develop revision

**Files:**
- Verify: `Jenkinsfile.benchmark`
- Verify: `src/test/java/com/heojungseok/openmetadatasync/jenkins/JenkinsPipelineContractTest.java`
- Verify: `README.md`
- Verify: `docs/superpowers/plans/2026-08-09-jenkins-preflight-evidence-gate.md`

**Step 1: Run the clean suite**

Run:

```bash
./gradlew clean test
```

Expected: BUILD SUCCESSFUL with all tests passing.

**Step 2: Inspect the exact diff and request independent review**

Check `git diff --check`, the scoped diff, and ask the existing Jenkins/observability reviewer to report Blocker/High findings. Do not run another Gradle build concurrently in the shared worktree.

**Step 3: Commit and push only the approved scope**

Stage the four files, verify the cached diff, commit as:

```text
fix: fail Jenkins on rejected preflight evidence
```

Push `develop`, then verify local HEAD, `origin/develop`, and `git ls-remote` match exactly.

### Task 5: Prove the runtime restart gate in Jenkins

**Files:**
- Runtime evidence only: `benchmark-evidence/benchmark-100000-initial.{json,md}`
- Runtime evidence only: `benchmark-evidence/benchmark-100000-no-op.{json,md}`

**Step 1: Run the initial restart pair on the pushed SHA**

Use a new deterministic seed and one immutable request ID:

1. `BENCHMARK_GATE=PREFLIGHT`, `WORKLOAD_SCENARIO=initial`, `FAIL_FIRST_EXECUTION=true` → expected Jenkins `FAILURE`.
2. Repeat identical identifying parameters → expected resume, Markdown `Preflight gate | PASS`, Jenkins `SUCCESS`.

**Step 2: Run the no-op restart pair on the same pushed SHA**

Use the same new seed and a different immutable request ID:

1. `BENCHMARK_GATE=PREFLIGHT`, `WORKLOAD_SCENARIO=no-op`, `FAIL_FIRST_EXECUTION=true` → expected Jenkins `FAILURE`.
2. Repeat identical identifying parameters → expected resume, Markdown `Preflight gate | PASS`, Jenkins `SUCCESS`.

**Step 3: Reconcile proof before main integration**

Confirm both archived Markdown files contain the exact PASS line, the console records the intended first failures and second successes, and all four builds used the same pushed `develop` SHA. Do not integrate into `main` without separate approval.
