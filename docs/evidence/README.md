# Evidence boundary

Evidence has three distinct owners:

- Spring Batch metadata tables and `sync_chunk_result` are the durable execution/restart SSOT. Database verification and business status decide whether work is complete.
- Structured application logs are transient observability only. `commitCount`, `readCount`, and `writeCount` help operators observe progress, but no log line is a checkpoint.
- Jenkins keeps the machine-readable outcome property file and approved benchmark JSON/Markdown artifacts. Jenkins status and artifacts summarize a run; they do not replace Batch metadata or database reconciliation.

`SUCCESS`, `UNSTABLE`, `FAILURE`, and `NOT_BUILT` follow the mappings in the root README. For benchmark evidence, `Processing result PASS` means the Batch/data result is complete and internally reconciled. Restart, heap retention, and persistence are separate qualifications for a larger synthetic data-plane run. A qualification miss makes Jenkins `UNSTABLE`; it does not rewrite successful processing as an application failure. Outcome files contain only the result code/outcome, job name, request ID, mode, and execution ID. Database credentials remain masked environment variables and are not job parameters or artifacts.

Jenkins deletes only the exact current outcome target before launch and accepts the replacement only when code, request ID, job, and mode match the current build. Benchmark evidence schema `v2` records both retained-heap window floors, growth, allowance, and separate qualification verdicts. The Jenkins benchmark JVM uses fixed `-Xms128m -Xmx256m` settings so repeated synthetic data-plane runs share the same memory envelope. Heap retention is not a GC-health check and does not prove the memory behavior of future external-API collection. Benchmark evidence is fixed under `benchmark-evidence`: successful or unstable runs archive only the exact current JSON/Markdown pair, while `MAIN` also archives its exact 100k `initial` and `no-op` prerequisite pairs. An already-completed skip archives only the correlated outcome file.

Neither application launch nor either Jenkins pipeline performs automatic database/schema/volume/branch cleanup. Retention and any cleanup are separate, explicitly approved operations.
