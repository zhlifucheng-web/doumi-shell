# Self-update and automated releases implementation plan

> Execute inline in this session using the approved design; preserve all pre-existing dirty frontend files. Use the executing-plans workflow task by task and request an independent review at completion.

**Goal:** Ship Windows/Linux self-update, configurable GitHub acceleration, and reproducible GitHub release builds.

**Architecture:** Release resolution produces trusted stable artifact URLs. An authenticated server-side coordinator prepares a locked job and launches a standalone Java installer; the installer backs up stopped files, installs, probes health and rolls back. CI packages the same updater with both distributions.

**Tech Stack:** Spring Boot/Java 17, Vue 3/Element Plus, dependency-free Java installer, Node 24 tests, Python packaging, PowerShell/WiX MSI, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-29-self-update-design.md`

## Global constraints

- Windows x64 MSI and Linux x64 tar.gz; Java 17 minimum; Node 24; Python >=3.11.
- Stable releases only, repository `zhlifucheng-web/doumi-shell`, exact filenames and SHA-256 required.
- Prefix proxies are configurable; blank means direct GitHub. No secrets in responses or browser storage.
- No push, deployment or public Release during local work. Preserve existing edits.

### Task 1: trusted release and settings

Files: `SshReleaseService.java`, new `update/UpdateSettings.java`, release service tests, frontend release tests/utilities.
Interfaces: `latest(boolean)` returns version and all four trusted URLs; settings `proxy()` / `saveProxy(String)` / `authorize(String)`; transport `route(String)`.

- [x] Write tests that expect GitHub exact filenames, complete checksum assets, draft rejection, valid/invalid proxy prefixes, persisted settings and constant-time management token authorization.
- [x] Run `mvn -o -Dtest=SshReleaseServiceTest,UpdateSettingsTest test` and observe missing/new behavior failures.
- [x] Implement fixed repository resolution and settings with private atomic files, invalidate release cache after proxy changes.
- [x] Re-run targeted Maven and `node --test tests/releaseCheck.test.js` tests.

### Task 2: standalone installer and coordinator

Files: new `com/doumiot/updater/*.java`, `sshconsole/update/*.java`, `SshUpdateController.java`, new installer/coordinator tests; Maven updater classifier.
Interfaces: `UpdateInstaller.main(String[])` consumes private `job.properties`; `UpdateService.status()`, `start(String version)`, `health(String job)`; JSON state contains phase, target version, progress and safe message.

- [x] Write real temporary-directory tests for checksum mismatch, tar traversal/symlink rejection, backup/restore and health failure rollback. Write service/controller tests for auth, settings, unsupported layouts, duplicate jobs and maintenance handling.
- [x] Run new tests to observe missing features.
- [x] Implement bounded downloads, private staging, file locks, maintenance/draining, helper handoff and shutdown; helper uses offline program/data backup and candidate health before success.
- [x] Verify targeted tests and build standalone helper JAR. Keep updater failures inspectable in job-local logs/state.

### Task 3: update page

Files: `About.vue`, new `UpdatePanel.vue`, `utils/selfUpdate.js`, frontend request tests, release/version links.
Interfaces: client `updateRequest(path, {token, body, method})` validates existing Result envelope. Poll status through restarts and reload only when update succeeds.

- [x] Test token header, timeout/envelope failures, settings body, trusted GitHub links and error recovery.
- [x] Implement existing Element Plus styling, phase/progress feedback, non-persisted password input, proxy save, install confirmation, restart polling and accessible error text.
- [x] Run frontend tests and production build.

### Task 4: packaging and Actions

Files: `.github/workflows/build.yml`, `.github/workflows/release.yml`, packaging scripts, MSI upgrade resource, README and updater guide.
Interfaces: release builds package `doumi-updater.jar` next to app JAR; all outputs have matching validated version and checksums.

- [x] Add packaging fixture tests proving helper inclusion, safe names and embedded-version rejection.
- [x] Package helper through Maven jar classifier; reuse Windows scripts, Linux packaging and add cross-platform frontend/backend build script.
- [x] Add PR/main CI and tag/manual builds. Only tag builds publish after validating both platform outputs; draft remains unpublished on upload failure.
- [x] Run fixture tests, parse workflows, build/package locally and inspect archive contents/version/hash. Independently review diff, repair findings, run final relevant checks and report native/hosted verification limits.

## Verification record

Local verification on 2026-10-29:

- Backend Maven `verify`: 308 tests, 0 failures, 0 errors, 2 skipped; application and standalone updater JAR built successfully. Local runtime JBR 21, source/target 8 for Spring scanner compatibility; CI targets JDK 17.
- Frontend Node 24: 336 tests passed; Vite production build succeeded with the existing large-chunk warning.
- Python packaging/publishing fixture tests: 2 passed. Both workflow YAML files parsed successfully.
- Linux distribution built from real JARs in an isolated temporary directory. Confirmed embedded version, standalone helper manifest, program/launcher/config contents, no data/token inclusion, and SHA-256.
- Browser preview: saved `https://gh-proxy.com/`, confirmed persistence after reload and cleared management-token input; no browser errors or warnings. Used isolated temporary data and a test-only token; no update installed.
- Independent review found no concrete blockers, including the final candidate maintenance and background-backup admission changes. `git diff --check` passed.

Validation limits: Windows native MSI build/install/rollback and hosted GitHub Actions have not run in this macOS session. No public release, push, commit, or deployment was performed. Initial upgrade to a package containing the updater remains manual.
