# Jenkins preflight evidence gate design

## Purpose and scope

Make the Jenkins benchmark `PREFLIGHT` result fail closed: a completed Batch process and existing evidence files are not enough; the current Markdown evidence must contain the exact generated verdict `| Preflight gate | PASS |` before Jenkins reports `SUCCESS` or `UNSTABLE`.

The change is limited to `Jenkinsfile.benchmark`, its static contract test, and Jenkins operator documentation. `main` remains untouched until the verified `develop` revision completes the actual Jenkins restart gate.

## Current facts

- `develop@42c4622` completed a 100k `no-op` Batch run with process code `0` and Jenkins `SUCCESS`.
- Its evidence correctly recorded `Restart gate | false` and `Preflight gate | FAIL` because `FAIL_FIRST_EXECUTION` was not selected.
- `Jenkinsfile.benchmark` currently validates only the correlated outcome file and the existence of the current JSON/Markdown evidence files.
- `BenchmarkEvidence` computes the Markdown verdict by running the complete preflight contract: scenario semantics, 100k rows, reconciliation, checksums, row integrity, restart proof, heap plateau, and JDBC batch evidence.
- A 1M `MAIN` evidence file is not a 100k preflight verdict. `MAIN` already validates both persisted 100k profiles inside the application before launching the million-row work, so the new current-file verdict check applies only to `PREFLIGHT`.

## Target behavior

For an application status of `0` or `2`:

1. Require the current JSON and Markdown evidence files.
2. When `BENCHMARK_GATE=PREFLIGHT`, additionally require the exact full Markdown line:

```text
| Preflight gate | PASS |
```

3. If the line is absent, emit a readable console record and set the Jenkins result to `FAILURE`:

```text
BENCHMARK_GATE_FAILURE [벤치마크 판정 실패] gate=PREFLIGHT evidence=benchmark-evidence/benchmark-100000-no-op.md reason=PREFLIGHT_NOT_PASS
```

4. If the line is present, preserve the existing `status` mapping: `0 → SUCCESS`, `2 → UNSTABLE`.
5. Technical failure (`1`), already-completed (`3`), lock miss, outcome mismatch, artifact selection, and `MAIN` behavior remain unchanged.

The pipeline does not print file contents, credentials, or arbitrary evidence values.

## Ownership and alternatives

### Selected: Jenkins validates the generated Markdown verdict

The application owns evidence computation; Jenkins owns CI admission. An exact `grep -Fqx` against a generated fixed line uses tools already present on the agent and adds no dependency.

### Rejected: convert preflight evidence failure into an application exit code

This would turn a successfully completed data job into a failed Batch execution and mix business processing status with CI qualification. It would also broaden application and evidence-writing behavior beyond the observed Jenkins defect.

### Rejected: add a JSON field and parser

This would change the evidence schema and require a parser or fragile JSON text matching. The existing Markdown verdict is already derived from `requirePreflight()` and is sufficient for the current local Jenkins agent.

## Failure scenarios

- Missing evidence file: existing `FAILURE` behavior remains.
- Evidence exists but verdict is `FAIL`: Jenkins becomes `FAILURE` with the explicit Korean diagnostic line.
- First injected-restart run fails before evidence: application status `1` maps to Jenkins `FAILURE` as before.
- Second run resumes and writes PASS evidence: Jenkins becomes `SUCCESS` when the process code is `0`.
- A stale unrelated `PASS` line cannot be read from a different scenario or row-count path because the pipeline checks the exact current evidence path selected from the fixed gate/scenario mapping.

## Test strategy

Extend `JenkinsPipelineContractTest` to require, in the benchmark pipeline only:

- the exact `grep -Fqx '| Preflight gate | PASS |'` check;
- the check to be conditional on `BENCHMARK_GATE == 'PREFLIGHT'`;
- the `BENCHMARK_GATE_FAILURE` Korean console event;
- gate failure to participate in the existing `evidenceValid`/`FAILURE` decision;
- no equivalent current-file PASS requirement in the Crossref pipeline.

Record focused RED and GREEN, then run `./gradlew clean test`. Static tests prove the Jenkinsfile contract only; the final proof requires the two-process restart sequence on the exact pushed `develop` SHA.

## Risk, review, and rollback

Risk score: impact scope 1, failure impact 1, reversibility 0, runtime uncertainty 1; total 3, medium. One independent Jenkins/observability review is required after implementation or before completion.

Rollback is a Git revert of the Jenkinsfile/test/documentation commit. No database, Batch metadata, evidence, branch, or Jenkins configuration cleanup is part of rollback.

## Completion conditions

- Focused contract RED and GREEN are recorded.
- The final clean test suite passes.
- Independent review has no unresolved Blocker or High finding.
- The implementation is pushed to `develop` and the remote SHA is verified.
- An injected first Jenkins run ends `FAILURE`.
- An identical second run resumes, writes `Preflight gate | PASS`, and ends `SUCCESS`.
- `main` remains unchanged until the required initial and no-op preflight evidence is complete and separately approved for integration.
