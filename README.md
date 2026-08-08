# Open Metadata Sync

Open Metadata Sync is a manual Spring Batch application. It exposes no scheduler, cron trigger, or HTTP/Admin launch API. `crossrefSyncJob` runs actual Crossref collection/sync/verification; `dataPlaneBenchmarkJob` runs the isolated synthetic benchmark and evidence step.

## Manual application launch

Provide database credentials through masked environment variables, never job parameters:

```bash
export DB_USERNAME='...'
export DB_PASSWORD='...'
./gradlew bootJar
```

Backfill example:

```bash
java -jar build/libs/open-metadata-sync-0.0.1-SNAPSHOT.jar \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=crossrefSyncJob \
  --spring.profiles.active=actual \
  --batch.outcome-file=build/manual/crossref-outcome.properties \
  requestId=manual-backfill-001,java.lang.String,true \
  mode=BACKFILL,java.lang.String,true \
  createdFrom=2026-08-01,java.time.LocalDate,true \
  createdUntil=2026-08-02,java.time.LocalDate,true \
  maxItems=1000,java.lang.Long,true \
  chunkSize=1000,java.lang.Long,false \
  hibernateBatchSize=1000,java.lang.Long,false
```

Benchmark example:

```bash
java -jar build/libs/open-metadata-sync-0.0.1-SNAPSHOT.jar \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=dataPlaneBenchmarkJob \
  --spring.profiles.active=benchmark-preflight \
  --batch.outcome-file=build/manual/benchmark-outcome.properties \
  requestId=manual-benchmark-001,java.lang.String,true \
  mode=BENCHMARK,java.lang.String,true \
  rowCount=100000,java.lang.Long,true \
  seed=20260808,java.lang.Long,true \
  generatorVersion=v1,java.lang.String,true \
  scenario=initial,java.lang.String,true \
  chunkSize=1000,java.lang.Long,false \
  hibernateBatchSize=1000,java.lang.Long,false \
  evidenceDirectory=benchmark-evidence,java.lang.String,false \
  failFirstExecution=0,java.lang.Long,false
```

The application launcher inserts the build's `syncContractHash` as an identifying parameter. Request/mode/business-contract parameters are identifying; tuning is non-identifying.

## Process results

The process exit code and the `batch.outcome-file` agree:

| Code | Outcome | Jenkins result |
|---:|---|---|
| `0` | clean completion | `SUCCESS` |
| `2` | business `COMPLETED_WITH_ERRORS` | `UNSTABLE` |
| `1` | technical, conflict, verification, or outcome-write failure | `FAILURE` |
| `3` | same identifying parameters already completed | `NOT_BUILT` |

An already-completed launch emits one `BATCH_LAUNCH_SKIPPED reason=ALREADY_COMPLETED ... existingExecutionId=...` record and does not retry. The common lifecycle listener emits job/step start, end, and failure records; chunk progress is limited to every 100 commits or 60 seconds, with immediate error and final counter records. These logs are operational signals, not restart checkpoints.

## Jenkins

Create manual Pipeline jobs pointing to `Jenkinsfile.crossref` and `Jenkinsfile.benchmark`. Both require the Lockable Resources plugin resource `open-metadata-sync-data-plane` and the masked username/password credential `open-metadata-sync-db`. The non-waiting shared lock covers application launch, verification/evidence completion, outcome validation, and artifact archival. A build that cannot enter the lock is `NOT_BUILT` and launches no application.

The pipelines do not commit, push, delete branches, clean schemas/databases/volumes, or perform automatic cleanup. Jenkins execution is a separate gate; local tests validate the pipeline contracts but do not prove a controller/plugin execution.

See [docs/evidence/README.md](docs/evidence/README.md) for evidence ownership.
