# Crossref Stable Cursor Compatibility Design

## Context

Crossref cursor pagination can return the same `next-cursor` token while advancing server-side state. The 100,000-item Jenkins BACKFILL therefore fails after the first full page because `CrossrefCollector` currently treats an unchanged cursor string as collection stagnation. The existing 10-item smoke test did not cross a page boundary.

## Design

Allow a non-blank `next-cursor` to be reused even when its string equals the current cursor. Keep the existing safety controls unchanged:

- a full page must include a non-blank next cursor;
- the derived page bound and configured page safety cap limit requests;
- one previous full page is retained in memory so an identical consecutive payload is retried once and then rejected;
- consecutive pages that add no new staging rows fail collection.

No new configuration, fingerprint, persistence column, or migration is added.

## Data Flow

The collector fetches a page, validates the full-page cursor, compares it with the previous full page, persists distinct page items and durable progress, then supplies the returned cursor token to the next request. A stable token is accepted because Crossref may advance its position server-side. An identical consecutive full-page payload is not persisted; it is retried once and fails at the existing consecutive-no-progress limit if repeated again. Fetch attempts continue to consume the global safety cap and appear in evidence, while only distinct payload pages consume the total-results-derived page bound. Memory remains bounded to one page.

## Testing

Add one collector regression test in which two different full pages return the same cursor token and collection reaches `maxItems`. Add tests for repeated identical payload failure, blank cursor precedence and a transient repeated page followed by a short final page. Verify duplicate fetches do not persist rows or consume the derived page bound, while they still count toward the global cap and evidence. Verify each test fails for the intended reason before the corresponding production change, then rerun the focused and full suites.

## Completion Conditions

- focused collector tests pass;
- the full Gradle test suite passes;
- Jenkins checks out the final `develop` SHA;
- the actual Crossref BACKFILL collects and processes 100,000 items successfully.
