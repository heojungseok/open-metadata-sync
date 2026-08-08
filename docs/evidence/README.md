# Evidence boundary

Evidence has three distinct owners:

- Spring Batch metadata tables and `sync_chunk_result` are the durable execution/restart SSOT. Database verification and business status decide whether work is complete.
- Structured application logs are transient observability only. `commitCount`, `readCount`, and `writeCount` help operators observe progress, but no log line is a checkpoint.
- Jenkins keeps the machine-readable outcome property file and approved benchmark JSON/Markdown artifacts. Jenkins status and artifacts summarize a run; they do not replace Batch metadata or database reconciliation.

`SUCCESS`, `UNSTABLE`, `FAILURE`, and `NOT_BUILT` follow the mappings in the root README. Outcome files contain only the result code/outcome, job name, request ID, mode, and execution ID. Database credentials remain masked environment variables and are not job parameters or artifacts.

Neither application launch nor either Jenkins pipeline performs automatic database/schema/volume/branch cleanup. Retention and any cleanup are separate, explicitly approved operations.
