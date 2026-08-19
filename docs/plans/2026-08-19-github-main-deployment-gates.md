# GitHub Main Deployment Gates

## Scope

Make an exact protected `main` SHA the only public-demo deployment candidate. GitHub Actions validates pull requests and final `main` commits; Jenkins keeps the external integration and public-demo jobs. Deployment remains manual.

## Invariants

- A `develop` update alone cannot deploy.
- The approved candidate, clean `HEAD`, live canonical `main`, image tag, `DEMO_INFRA_REVISION`, and `DEMO_REVISION` must match.
- A pull request merge never changes the running demo.
- Docker build, runtime cutover, restore, and cleanup require a separate approval.
- MySQL data and Jenkins home volumes survive targeted container replacement.

## Gates

### 1. GitHub bootstrap

- Enable Dependency graph, Dependabot alerts, and security updates.
- Allow only GitHub-owned Actions and require full commit SHA pins.
- Require pull requests on `main`; block deletion and force-push.
- Add no required check until the first real check succeeds.

### 2. PR A: minimal CI

- Add `.github/workflows/ci.yml` for `pull_request` to `main` and `push` to `main`.
- Run Java 21 `./gradlew clean test --no-daemon` on a GitHub-hosted runner.
- Use read-only contents permission, no saved checkout credentials, no secrets, cache, Docker publish, or deployment.
- Add the official Gradle distribution checksum.
- After the first green pull-request run, require the observed `CI / test` check and an up-to-date branch. Verify the final merged `main` SHA separately.

### 3. PR B: exact-main candidate contract

- Accept an explicitly approved full `CANDIDATE_REVISION`; never choose one automatically.
- Fail unless the worktree is clean and the candidate equals both `HEAD` and live `https://github.com/heojungseok/open-metadata-sync.git` `refs/heads/main`.
- Recheck live `main` before image build and immediately before stopping runtime traffic.
- Use `DEMO_INFRA_REVISION` as the external revision SSOT and map it to application revision fields.
- Remove the fixed application revision and partial-diff exception.
- Replace only custom demo containers; keep the MySQL container and named data/Jenkins volumes.

### 4. Separately approved cutover and cleanup

- Record exact-main CI and immutable image-label proof.
- Drain Jenkins, perform targeted replacement, then run deployed smoke tests.
- Create and scratch-restore a fresh recovery export.
- Only after those proofs, remove superseded custom containers and images by explicit image ID; retain current images, DB volume, Jenkins home, and recovery export.

## Rollback

- GitHub: remove the required check or disable the new ruleset before reverting PR A.
- Before cutover: revert PR B normally.
- After cutover: restore preserved prior images/config; restore data only from a verified recovery export when required.
