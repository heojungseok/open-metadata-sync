# Repeatable Replay Demo Design

## Status

- State: user-approved
- Approved direction: automatically restore only the isolated replay demo fixture and show an `OPEN -> RESOLVED`, `0 -> 1 target row`, `INSERTED` transition in Jenkins.
- Repository baseline: `codex/demo-infrastructure@d9d18eba9a094c6032a2d8ac4d01e549f6ce1352`

## Purpose and scope

Make the Jenkins `open-metadata-sync-demo/오류 재처리 데모` job repeatable for external demonstrations. Every demo build must begin from the same controlled failed-write fixture, run the existing `REPLAY_ERRORS` application path, and expose human-readable before/after evidence in the Jenkins console and archived artifacts.

The change is limited to the demo Jenkins branch, its isolated MySQL instance at `127.0.0.1:3308`, and the fixed DOI `10.5555/demo-replay`. The normal Crossref Jenkins path, application replay contract, actual/benchmark databases on port `3307`, README, resume, and portfolio wording are out of scope.

## Current facts

- `Jenkinsfile.crossref` uses the caller-provided `SOURCE_EXECUTION_ID` for normal jobs.
- Only jobs under `open-metadata-sync-demo/` replace that parameter with the folder property `DEMO_REPLAY_SOURCE_EXECUTION_ID`.
- The demo folder property is `00000000-0000-0000-0000-00000000d001` and points to the isolated `open_metadata` schema on port `3308`.
- `scripts/demo-replay-fixture.sql` currently restores one `OPEN` source error but also leaves the target DOI present, so a successful replay produces the internal `NO_OP` outcome.
- `scripts/demo-replay-summary.sh` currently verifies only the post-run state and archives one post-run JSON/Markdown pair.

## Target state

For demo jobs only, immediately before launching the existing application replay job:

1. Require an explicit demo reset acknowledgement and exact isolated-container contract.
2. Restore the fixed source execution and one `OPEN` error describing a simulated transient write failure.
3. Remove only the fixed target DOI from the isolated demo schema so the pre-run target count is zero.
4. Verify and print the before state.
5. Archive before-state JSON and Markdown.
6. Run the unchanged `REPLAY_ERRORS` application path.
7. Verify and print the after state: source error `RESOLVED`, `replay_count=1`, replay execution `COMPLETED`, inserted outcome `1`, target count `1`, and no new replay errors.
8. Archive after-state JSON and Markdown.

Normal Jenkins jobs continue to accept arbitrary valid `SOURCE_EXECUTION_ID` values and never invoke the demo reset or demo evidence scripts.

## Alternatives

### Selected: Jenkins console plus archived before/after artifacts

This keeps the demonstration inside the already authenticated Jenkins surface. Console lines provide immediate narration, while JSON and Markdown preserve durable evidence without exposing database credentials.

### Rejected: console output only

This is smaller but makes later comparison and artifact review difficult.

### Rejected: external database browser

This would add another externally reachable component, credential surface, and authorization problem solely for presentation.

## Components and responsibilities

### `scripts/demo-replay-fixture.sql`

Owns only deterministic fixture data. It sets the source error to `OPEN`, resets `replay_count`, changes the error metadata to describe a simulated transient write failure, and deletes only `work.doi = '10.5555/demo-replay'` in the already selected isolated schema.

### `scripts/demo-reset-replay.sh`

Owns the destructive boundary and before evidence. It rejects execution unless all of these hold:

- `DEMO_REPLAY_RESET_ACK=REPLAY_ERRORS`
- `DB_HOST` is `localhost` or `127.0.0.1`
- `DB_PORT=3308`
- `DEMO_DB_CONTAINER=open-metadata-sync-demo-mysql`
- `SOURCE_EXECUTION_ID=00000000-0000-0000-0000-00000000d001`

It runs the fixture SQL against only `open_metadata`, queries the fixed row, verifies the exact before state, prints one concise `BEFORE` line, and writes `replay-before-<REQUEST_ID>.json` and `.md`.

### `scripts/demo-replay-summary.sh`

Owns after evidence. It verifies the exact completed replay state, prints one concise `AFTER` line, and writes `replay-after-<REQUEST_ID>.json` and `.md`.

### `Jenkinsfile.crossref`

Keeps one production-capable pipeline. Inside the existing `demoJob` conditional only, it invokes the reset script before Java, archives before evidence even when the application run fails after reset, invokes the after summary only on a successful valid demo outcome, and archives after evidence. Non-demo control flow and parameters stay unchanged.

## Demonstration flow

```text
BEFORE: source=FAILED error=OPEN code=DEMO_TRANSIENT_WRITE doi=10.5555/demo-replay replay_count=0 target_rows=0
REPLAY_ERRORS: existing application job processes one frozen OPEN error
AFTER: replay=COMPLETED error=RESOLVED doi=10.5555/demo-replay replay_count=1 inserted=1 target_rows=1
```

The internal `NO_OP` metric is not used in this replay demonstration. The separate 10K `NO_OP` benchmark remains unchanged.

## Failure handling and recovery

- Any safety-guard mismatch fails before SQL execution.
- Any unexpected fixture row count or value fails before the application launch.
- If the application fails after reset, the before artifacts remain available and the source error stays eligible according to the existing replay lifecycle.
- Re-running the demo job with a new `REQUEST_ID` restores the same controlled fixture and can demonstrate the transition again.
- Rollback is a Git revert of the Jenkins/scripts change. Existing application replay behavior and normal job parameters require no data migration or rollback.

## Testing and completion conditions

Contract tests must first fail, then pass, proving:

- the reset script has every exact guard;
- the fixture deletes only the fixed demo DOI and never names port `3307` or benchmark schemas;
- only the `demoJob` branch invokes the reset script;
- normal `SOURCE_EXECUTION_ID` handling remains present;
- Jenkins archives the exact before and after filenames;
- summary expectations use `inserted_count=1` and `target_count=1`, not `no_op_count=1`.

Runtime verification against the isolated container must prove:

1. reset produces `FAILED/OPEN/replay_count=0/target_count=0`;
2. application replay produces `COMPLETED/RESOLVED/replay_count=1/inserted_count=1/target_count=1`;
3. a second reset returns to the exact before state;
4. the 10K preflight schema and port `3307` databases are not modified;
5. focused tests, full tests, shell syntax checks, and `git diff --check` pass on the final revision;
6. a Jenkins build on the final `develop` SHA archives all four before/after artifacts.

## Change analysis and risk

- Impact scope: 1 — one Jenkins pipeline's demo-only branch and two demo scripts.
- Failure impact: 1 — a bad demo reset can damage disposable isolated fixture data but guards prevent broader targeting.
- Reversibility: 0 — deterministic fixture reload and Git revert restore behavior.
- Verification uncertainty: 1 — local tests plus real Docker and Jenkins execution are required.
- Total: 3, medium.
- Forced-high conditions: none. The only deletion is an approved, deterministic, disposable row in an isolated demo schema and is immediately recoverable from the fixture.

One independent implementation/evidence review is required at the combined Gate 5/7 checkpoint after runtime verification. No schema migration, public API change, credential change, external network exposure, or irreversible write is introduced.

## Assumptions and constraints

- The existing isolated container, folder credential, folder properties, and Jenkins project authorization remain in place.
- Each demonstration uses a new immutable `REQUEST_ID`.
- Quick Tunnel remains off until the separate external-access gate; this change does not alter Cloudflare configuration.
- README, resume, and portfolio wording remain owned by the separate documentation task.
