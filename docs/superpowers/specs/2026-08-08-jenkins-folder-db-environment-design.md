# Jenkins Folder-scoped DB environment design

Status: user-approved

## 1. Purpose and scope

`crossref` and `benchmark` Pipeline jobs read `DB_HOST` and `DB_PORT` from their common `open-metadata-sync` Jenkins Folder instead of Jenkins global properties. The change is limited to both Jenkinsfiles, their contract test, and operator documentation.

## 2. Current facts

- The baseline is clean `develop@43b96270a13725218aa35aeaba820fbb54943d46`, equal to `origin/develop` when this design was written.
- Both Jenkinsfiles currently rely on ambient `DB_HOST` and `DB_PORT` values.
- `open-metadata-sync-db` is already bound through Jenkins Credentials Binding.
- The user removed the temporary Jenkins-global `DB_HOST` and `DB_PORT` values.

## 3. Assumptions

- Jenkins 2.548 can install the Folder Properties plugin, whose current minimum Jenkins version is 2.479.1.
- Both jobs will live below one `open-metadata-sync` Folder.
- The Folder will define `DB_HOST=localhost` and `DB_PORT=3307` for the current native-Jenkins/Docker-MySQL topology.

If the plugin cannot be installed or the jobs cannot share a Folder, implementation stops rather than falling back to global properties.

## 4. Target state

- Both Pipeline stages clear ambient `DB_HOST` and `DB_PORT` values before executing their existing logic inside `withFolderProperties`.
- Each Pipeline rejects missing, empty, or nonnumeric-format Folder values before acquiring the shared lock or launching Java.
- Neither DB address becomes a user build parameter, credential value, or hard-coded repository setting.
- DB username/password remain a Folder-scoped username/password credential with ID `open-metadata-sync-db`.

## 5. Existing design assumptions

The two manual Pipeline jobs share one data plane, credential identity, and non-waiting lock while retaining different job parameters and artifacts.

## 6. Constraints

- No scheduler, webhook trigger, Admin launch API, schema change, data cleanup, volume cleanup, or Git automation is added.
- Folder Properties and Lockable Resources are explicit Jenkins runtime dependencies.
- Jenkins controller success remains unproved until an actual build runs.

## 7. Broken assumption

The previous implementation assumed a Jenkins-global or process-global DB address. That is too broad for a controller that may host unrelated projects and uses collision-prone variable names.

## 8. Impact

- CI/CD: adds the `withFolderProperties` runtime step and Folder configuration prerequisite.
- Security/configuration: narrows DB address visibility and keeps secrets in the existing scoped credential.
- Application, database, network protocol, artifacts, Batch metadata, and business parameters: unchanged.
- Tests/docs: Jenkins contract tests and setup instructions change.

## 9. Alternatives

1. **Folder properties — selected.** One project-scoped definition shared by two jobs; environment-specific values stay out of Git.
2. Per-Pipeline hard-coded `environment` blocks — rejected because it duplicates values and commits local topology into source.
3. Jenkins-global properties — rejected because unrelated jobs inherit generic `DB_HOST` and `DB_PORT` names.

## 10. Expected benefit

The two project jobs share one explicit environment boundary without exposing configuration to unrelated Jenkins items or duplicating it across Pipeline definitions.

## 11. Risks and failure scenarios

- Missing plugin or Folder values: cleared ambient values cannot act as a fallback, so the Pipeline fails before lock acquisition and application launch.
- Incorrect host/port: application launch fails and Jenkins maps the technical failure to `FAILURE`.
- Jobs placed outside the Folder: missing-property validation fails closed.

## 12. Readiness and resilience

Readiness requires the Folder Properties plugin, both Folder properties, Folder-scoped credential, shared lock resource, Java 21, and reachable MySQL. Runtime DB/network recovery remains owned by the existing application and is unchanged.

## 13. Completion and verification

- A focused Jenkins contract test must fail before implementation and pass after it.
- `./gradlew clean test` must pass on the final `develop` revision.
- Independent infrastructure/operations review must find no unresolved Blocker or High issue.
- Actual Jenkins builds must later prove Folder property resolution; local string-contract tests cannot prove plugin runtime behavior.

## 14. Rollback and recovery

Revert the Jenkinsfile/test/docs commit and restore the previous Jenkins environment source. No database or application-data rollback is required.

## 15. User decisions

The user selected project-specific configuration, removed global values, and explicitly requested implementation. No additional decision is open within this scope.

## 16. Evidence

- `Jenkinsfile.crossref`
- `Jenkinsfile.benchmark`
- `src/test/java/com/heojungseok/openmetadatasync/jenkins/JenkinsPipelineContractTest.java`
- `README.md`
- Jenkins Folder Properties official plugin and Pipeline-step documentation, checked 2026-08-08

## Risk score and review gate

- Impact scope: 1 — two CI jobs and one Folder.
- Failure impact: 1 — reversible launch/configuration failure; no intended data mutation before validation.
- Reversibility: 0 — Git revert and Jenkins configuration restoration.
- Verification uncertainty: 1 — real Jenkins/plugin execution is required.
- Total: 3/8, medium; no forced-high condition.
- Review: one independent infrastructure/operations reviewer after implementation and Gate 6 evidence.
