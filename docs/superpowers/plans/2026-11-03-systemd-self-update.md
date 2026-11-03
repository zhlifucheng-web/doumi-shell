# systemd Self-update Implementation Plan

> **For agentic workers:** Execute inline with verification checkpoints; use executing-plans for task-by-task implementation. Steps use checkbox tracking.

**Goal:** Enable the existing browser updater for the current root-owned Linux systemd service while preserving version 1.0.63.

**Architecture:** Discover the owning system unit from cgroups and verify its MainPID and working directory. Launch the existing offline updater in a separate transient systemd service. Control only the verified original unit and use a temporary unit-specific health nonce override, including for rollback to older application versions.

**Tech Stack:** Java 17, JUnit 5, systemd 219-compatible systemctl/systemd-run, existing Spring/Vue updater.

**Spec:** User request “增加适配”; constraints below extend docs/self-update.md.

## Global Constraints

- Retain version 1.0.63, HTTP download proxy, GitHub Token encryption, SHA-256 and TLS verification.
- Initially support root-owned system units only; reject containers, user units and unverified service ownership.
- Never accept a service name or command from the browser. Never modify the persistent unit or service.env.
- The helper must survive stopping the app cgroup; use systemd-run, not a detached child of the app.
- Runtime health drop-ins are unit-specific, contain only a generated nonce, and must be removed after success or rollback.
- Linux program backup/restore touches only the release program allowlist. Preserve nginx, service.env, runtime and historical archives.
- Preserve the unrelated untracked scripts/tests/test_publish_assets.pyc.

### Task 1: Verified service discovery and command execution

Files: add `shell-end/src/main/java/com/doumiot/updater/SystemdControl.java` and `shell-end/src/test/java/com/doumiot/updater/SystemdControlTest.java`.

Interfaces: `SystemdControl.owningUnit(String): String`, `validateUnit(String)`, `show(String): Properties`, `launchCommand(String,Path,Path): List<String>`, and a service lifecycle with `stop()`, `start()`, `running()`, `close()`.

- [x] Add red tests: parse both cgroup v1/v2 system.slice service names; reject traversal/flags/user sessions; assert transient helper command contains a distinct unit and fixed executable paths.
- [x] Add root-only capability checks using `/proc/self` ownership, executable systemctl/systemd-run, matching MainPID and installation WorkingDirectory.
- [x] Test runtime drop-in CREATE_NEW ownership, cleanup and preservation of unrelated drop-ins using a temporary directory and a fake command runner.
- [x] Run `mvn -o -Dtest=SystemdControlTest test` with the configured JDK.

### Task 2: Offline updater lifecycle and rollback

Files: modify `UpdateInstaller.java`, `UpdateFiles.java`, `UpdateInstallerTest.java` in their existing updater packages.

- [x] Add red integration tests for service stop-before-backup, service candidate start, failed-health rollback and final cleanup, with real fixture child processes behind an injected service lifecycle.
- [x] In systemd mode, write helper-ready, wait for app handoff, acquire the global lock, and let install stop the verified service. Keep the standalone exit/wait behavior unchanged.
- [x] Reuse existing install/data recovery; route service stop/start/health through the lifecycle. Stop a failed candidate before restoring data, and retain ROLLBACK_FAILED when safe stop/cleanup cannot be confirmed.
- [x] For Linux, back up and restore only `UpdateFiles.programFile` entries plus the docs directory. Test that archives, nginx/service configuration and unrelated files survive both outcomes.
- [x] Run updater and managed gateway regression tests.

### Task 3: Application handoff, documentation and deployment

Files: modify `UpdateService.java`, related tests, `docs/self-update.md`, `README.md`, `CHANGELOG.md`.

- [x] Enable support status only after systemd ownership/capability validation; leave non-root/user/container deployments disabled.
- [x] Persist server-created systemd metadata; launch the helper with systemd-run, wait for durable readiness, then signal handoff. Do not exit the initiating JVM directly in systemd mode.
- [x] Release the initiating maintenance gate if the helper aborts and the old service stays alive. Use the existing nonce health endpoint through a temporary DOUMI_UPDATE_HEALTH_ID unit override.
- [x] Build frontend and run full backend/frontend/script verification plus independent review.
- [x] On the deployment host, create a loopback-only isolated fixture service with disposable data. Exercise success and forced health failure through the real transient helper. Clean up only fixture units/files and prove the production service/container baseline unchanged.
- [x] Commit scoped changes on main, back up production offline, deploy as 1.0.63, verify API support status, resources, data/key/settings integrity, original service and guacd health. Do not publish a tag/release or trigger a production upgrade to another version.

## Verification evidence before deployment

- Backend `clean verify`: 400 tests, 0 failures/errors, 2 skipped; frontend 444 passed; script tests 18 passed; frontend build passed.
- Independent review found no blocker, including the final systemd 219 compatibility change.
- Actual systemd 219 rejects transient `WorkingDirectory` and `UMask` assignments. The launcher omits these properties; helper paths are absolute and the job directory is private (0700). Regression test failed before and passed after this change.
- Real target-host fixture run: success `SUCCEEDED`, forced health failure `ROLLED_BACK`, helper cgroup separate, runtime health drop-ins removed, program/data/key rollback verified, configuration/history preserved.
- Fixture evidence: `/disk01/doumi-systemd-fixtures/39ec7f18-18b2-4cbf-a17d-e185cc67c124/result.json`. Production hashes, service and running container IDs unchanged during rehearsal.
- Packaged version remains 1.0.63. No production install endpoint was invoked.

## Deployment and release follow-up

- The adaptation commit `f7621b4` was deployed as 1.0.63. Offline backup: `/disk01/web-shell-backups/20261103-103037-pre-f7621b4`.
- Production verification passed: supported=true, busy=false, original unit/settings/key/data preserved, HTTPS assets match the packaged JAR, no startup errors, guacd healthy, unrelated containers unchanged.
- The subsequent user request authorizes pushing remote main, publishing a new complete Release and deploying it. Version 1.0.65 supersedes the previous version-retention/no-publication constraint for this follow-up; existing published tags remain unchanged.
