# Human-readable Batch lifecycle logging design

## Purpose and scope

Make the existing `BATCH_*` lifecycle logs immediately understandable to a Korean-speaking operator or developer without losing stable machine-searchable prefixes and identity fields. The change is limited to `BatchLifecycleLoggingListener`, its focused tests, and operator documentation.

It does not change Batch execution, Job/Step status, restart behavior, database writes, Jenkins result mapping, credentials, or evidence artifacts.

## Current facts

- `BatchLifecycleLoggingListener` already logs Job, Step, chunk progress, and failure events through SLF4J at `INFO`.
- Identity comes from `JobExecution`; Step counters come from `StepExecution`.
- The successful Jenkins Crossref run at `develop@e5ec603` emitted `BATCH_JOB_START`, `BATCH_STEP_END`, and `BATCH_JOB_END`, but the final Job line contained no processing summary.
- The same run emitted a misleading first `BATCH_CHUNK_PROGRESS` with `commitCount=0 readCount=0 writeCount=0`; the final Step counters were correctly `commitCount=1 readCount=10 writeCount=10`.
- Logging tests inject a `Consumer<String>` through the package-private constructor. The production constructor delegates to `LOGGER::info`.

## Target behavior

Keep every existing `BATCH_*` prefix and English `key=value` identity field for filtering. Add short Korean labels and Korean counter names to the human-facing portion.

Representative output:

```text
BATCH_JOB_START [배치 시작] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11
BATCH_STEP_START [단계 시작] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21
BATCH_STEP_END [sync 단계 종료] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 | status=COMPLETED | 읽음=10 | 저장=10 | 걸러냄=0 | 커밋=1 | 롤백=0 | 스킵=0
BATCH_JOB_END [배치 종료] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 | status=COMPLETED | 읽음=10 | 저장=10 | 걸러냄=0 | 커밋=1 | 롤백=0 | 스킵=0
```

Failure logs retain only the exception type, never the message:

```text
BATCH_JOB_FAILURE [배치 실패] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 | status=FAILED | error=IllegalStateException
```

## Data sources and aggregation

- Job identity and status: `JobExecution`.
- Step identity and counters: the callback `StepExecution`.
- Job totals: sum all entries from `JobExecution.getStepExecutions()`.
- `스킵`: sum read, process, and write skip counts.
- `걸러냄`, `커밋`, and `롤백`: Spring Batch filter, commit, and rollback counters without reinterpretation.

Tasklet steps can contribute a commit even when their read/write counts are zero. The Job total is therefore a literal Spring Batch aggregate, not a claim that every commit wrote an item.

## Progress correctness

Do not log the first chunk as `0/0/0`. Emit periodic progress only from counters representing already committed work. Check progress at the start of a subsequent chunk, where the prior committed counters are available, and require at least one completed commit. The final Step line remains the authoritative counter summary when a job finishes before another progress boundary.

Progress remains throttled to every 100 committed chunks or 60 seconds. Chunk failure coordinates remain immediate and unchanged in meaning.

## Logging implementation

Keep the current explicit SLF4J `LoggerFactory` and injected `Consumer<String>`. Lombok `@Slf4j` would only generate the same logger field and would not improve behavior; retaining the constructor seam keeps exact log assertions simple.

No new dependency, logger category, log level, structured logging library, or Jenkins plugin is introduced.

## Alternatives

1. **Bilingual existing events — selected.** One line remains useful for both `BATCH_*` filtering and human reading.
2. Add separate Korean duplicate lines. Rejected because it doubles lifecycle noise and can drift from the machine line.
3. Replace all keys with Korean. Rejected because it breaks stable search expressions and downstream parsing expectations.

## Failure and security behavior

- Failure events use `ERROR` semantics only through their stable event name; the existing SLF4J emission level remains `INFO` for a single searchable lifecycle stream.
- Exception messages, credentials, URLs, and arbitrary execution-context values are not logged.
- `requestId` and `mode` keep the existing whitespace and equals-sign sanitization.
- Korean text is static source text, not operator input.

## Verification

Focused tests must prove:

- exact Korean Job start/end and Step start/end output;
- Job totals are summed from multiple `StepExecution` values;
- filter, commit, rollback, and all skip categories are represented correctly;
- the initial chunk does not emit a zero progress record;
- the 100-chunk and 60-second progress boundaries remain effective;
- failure exception messages and secret-like parameters remain absent.

Run:

```bash
./gradlew test --tests com.heojungseok.openmetadatasync.batch.observability.BatchLifecycleLoggingListenerTest
./gradlew clean test
```

An actual Jenkins benchmark run must then confirm console readability. Local tests cannot prove Jenkins console encoding or operator readability.

## Risk and rollback

Risk score: impact scope 1, failure impact 0, reversibility 0, verification uncertainty 1; total 2, medium. One independent observability/infrastructure review is required after implementation or before completion.

Rollback is a Git revert of the listener/test/documentation commit. No database or application-data rollback is required.

## Completion conditions

- Focused RED and GREEN are recorded.
- The final `clean test` passes.
- Independent review has no unresolved Blocker or High finding.
- The final commit is pushed to `develop` and the remote SHA is verified.
- A Jenkins benchmark run on that SHA visibly emits the bilingual lifecycle lines with non-misleading counters.
