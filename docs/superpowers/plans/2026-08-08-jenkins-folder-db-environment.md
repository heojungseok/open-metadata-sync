# Jenkins Folder-scoped DB Environment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make both manual Jenkins Pipelines resolve and validate `DB_HOST` and `DB_PORT` from their common Jenkins Folder before acquiring the data-plane lock.

**Architecture:** Clear ambient DB address variables, then use the Folder Properties plugin's native `withFolderProperties` step around each existing Pipeline script. Keep environment-specific values in Jenkins, preserve the existing Folder-scoped credential, and fail before lock/application launch when Folder properties are absent or syntactically invalid.

**Tech Stack:** Jenkins Declarative Pipeline, Folder Properties plugin, JUnit 5, AssertJ, Gradle

---

### Task 1: Pin the Folder environment contract with a failing test

**Files:**
- Modify: `src/test/java/com/heojungseok/openmetadatasync/jenkins/JenkinsPipelineContractTest.java`

- [x] **Step 1: Add the failing contract assertion**

Add a shared assertion and call it from both Pipeline tests:

```java
private static void assertFolderScopedDbEnvironment(String pipeline) {
    assertThat(pipeline)
            .contains("withEnv(['DB_HOST=', 'DB_PORT='])")
            .contains("withFolderProperties {")
            .contains("requireValue('DB_HOST', env.DB_HOST, '[A-Za-z0-9._-]+')")
            .contains("requireValue('DB_PORT', env.DB_PORT, '[1-9][0-9]{0,4}')")
            .doesNotContain("string(name: 'DB_HOST'", "string(name: 'DB_PORT'",
                    "DB_HOST=localhost", "DB_PORT=3307");
    assertThat(pipeline.indexOf("withEnv(['DB_HOST=', 'DB_PORT='])"))
            .isLessThan(pipeline.indexOf("withFolderProperties {"));
    assertThat(pipeline.indexOf("withFolderProperties {")).isLessThan(pipeline.indexOf("lock(resource:"));
    assertThat(pipeline.indexOf("requireValue('DB_HOST'")).isLessThan(pipeline.indexOf("lock(resource:"));
    assertThat(pipeline.indexOf("requireValue('DB_PORT'")).isLessThan(pipeline.indexOf("lock(resource:"));
}
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew test --tests com.heojungseok.openmetadatasync.jenkins.JenkinsPipelineContractTest
```

Expected: exit `1`; both Pipeline contract tests fail because `withFolderProperties` and DB property validation are absent.

### Task 2: Add the minimum Folder Properties boundary

**Files:**
- Modify: `Jenkinsfile.crossref`
- Modify: `Jenkinsfile.benchmark`
- Modify: `README.md`

- [x] **Step 1: Wrap both existing scripts with Folder Properties**

In both Jenkinsfiles, clear the two ambient values in an outer `withEnv`, then open `withFolderProperties` immediately before the existing `script` body. Close all three wrappers immediately before the existing `steps` closing brace, and insert these exact lines as the first statements inside `script {`:

```groovy
withEnv(['DB_HOST=', 'DB_PORT=']) { withFolderProperties { script {
```

```groovy
requireValue('DB_HOST', env.DB_HOST, '[A-Za-z0-9._-]+')
requireValue('DB_PORT', env.DB_PORT, '[1-9][0-9]{0,4}')
```

Do not move or otherwise change the existing Pipeline body. The outer clear prevents Jenkins global/node values from becoming a fallback; the inner Folder expander supplies only project-scoped values. The new validation must precede `lock(resource: 'open-metadata-sync-data-plane', skipIfLocked: true)` so missing configuration launches no application and acquires no data-plane lock.

- [x] **Step 2: Document the exact Jenkins prerequisite and setup**

Update the Jenkins section of `README.md` to state:

```text
Both jobs must be children of one `open-metadata-sync` Folder. Install the Folder Properties plugin, configure `DB_HOST` and `DB_PORT` on that Folder, and keep `open-metadata-sync-db` in the Folder credential store. The Pipelines validate both Folder properties before the shared lock and application launch; they do not accept DB address build parameters or hard-code a local address.
```

- [x] **Step 3: Run focused test and verify GREEN**

Run:

```bash
./gradlew test --tests com.heojungseok.openmetadatasync.jenkins.JenkinsPipelineContractTest
```

Expected: exit `0`; three Jenkins contract tests pass.

### Task 3: Final local verification and review packet

**Files:**
- Verify all changed files

- [x] **Step 1: Run the full clean suite**

Run:

```bash
./gradlew clean test
```

Expected: exit `0`; all tests pass with no failures, errors, or skipped tests.

- [x] **Step 2: Check diff scope and whitespace**

Run:

```bash
git diff --check
git status --short --branch
```

Expected: only both Jenkinsfiles, Jenkins contract test, README, and this plan are changed; no whitespace errors.

- [ ] **Step 3: Commit the implementation**

```bash
git add Jenkinsfile.crossref Jenkinsfile.benchmark README.md \
  src/test/java/com/heojungseok/openmetadatasync/jenkins/JenkinsPipelineContractTest.java \
  docs/superpowers/plans/2026-08-08-jenkins-folder-db-environment.md
git commit -m "fix: scope Jenkins database environment to folder"
```

- [ ] **Step 4: Independent infrastructure/operations review**

Review the fixed commit for Folder Properties runtime compatibility, fail-before-lock ordering, secret/configuration scope, manual-only behavior, and proof boundaries. Block integration/push on unresolved Blocker or High findings.

- [ ] **Step 5: Keep the Jenkins runtime claim open**

Do not claim Jenkins success from local tests. After the Folder plugin and properties are configured, run the actual Pipeline and record the build result, resolved connectivity, outcome artifact, and DB JobExecution evidence separately.
