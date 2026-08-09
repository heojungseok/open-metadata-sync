# Crossref Stable Cursor Compatibility Design

## Context

Crossref cursor pagination can return the same `next-cursor` token while advancing server-side state. The 100,000-item Jenkins BACKFILL therefore fails after the first full page because `CrossrefCollector` currently treats an unchanged cursor string as collection stagnation. The existing 10-item smoke test did not cross a page boundary.

## Design

Allow a non-blank `next-cursor` to be reused even when its string equals the current cursor. Keep the existing safety controls unchanged:

- a full page must include a non-blank next cursor;
- the derived page bound and configured page safety cap limit requests;
- consecutive pages that add no new staging rows fail collection.

No new configuration, fingerprint, persistence column, or migration is added.

## Data Flow

The collector fetches a page, persists its items and durable progress, then supplies the returned cursor token to the next request. A stable token is accepted because Crossref may advance its position server-side. If the API actually repeats page data, the existing zero-new-staging-row guard terminates the run.

## Testing

Add a collector regression test in which two full pages return the same cursor token and collection reaches `maxItems`. Verify the test fails before the production change, then remove only the cursor-string equality rejection and rerun the focused and full suites.

## Completion Conditions

- focused collector tests pass;
- the full Gradle test suite passes;
- Jenkins checks out the final `develop` SHA;
- the actual Crossref BACKFILL collects and processes 100,000 items successfully.
