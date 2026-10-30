# Windows RDP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add browser-based Windows remote desktop through guacd while preserving SSH.
**Architecture:** Separate RDP profiles reuse host IP and CredentialCipher. Java Guacamole bridge holds credentials and single-use session tickets. Vue owns desktop display/input. Linux launcher optionally owns gateway lifecycle.
**Tech Stack:** Java 17, Spring Boot 2.3, SQLite, Apache guacamole-common, Vue 3, guacamole-common-js, Bash, Docker.
**Spec:** docs/superpowers/specs/2026-10-30-windows-rdp-design.md

## Global Constraints
- Work only in new worktree on codex/windows-rdp; preserve original checkout.
- RDP is independent of existing SSH accounts/port; no unrelated refactors.
- No credentials in responses, URLs or logs; same-origin WebSocket; NLA and certificate validation by default.
- No firewall changes, no deployments, no Windows-native guacd claim.
- Avoid unnecessary secondary labels and descriptions. Let the layout do the explaining.

### Task 1: RDP profile persistence
Files: new rdp/RdpConnectionService.java, RdpConnectionController.java, RdpTarget.java; sqlite_schema.sql; Java tests.
Consumes: SshHostService.getById, CredentialCipher.encryptSecretJson/decryptToJson.
Produces: RdpConnectionService.resolve(long) -> RdpTarget, CRUD REST contract from spec.
- [x] Test encrypted storage, secret-free DTOs, blank-password preservation, invalid port/domain/name/disabled host, cascading delete and existing-DB schema.
- [x] Run failing focused tests; implement repository/service/REST; run green tests.

### Task 2: Guacamole session bridge
Files: new rdp/RdpProperties.java, RdpSessionService.java, RdpSessionController.java, RdpWebSocketHandler.java; pom.xml; SshConsoleWebSocketConfig.java; Java tests.
Consumes: RdpConnectionService.resolve(long) -> RdpTarget.
Produces: POST/DELETE sessions, GET gateway, guacamole WebSocket contract from spec.
- [x] Add tests using fake guacd for RDP negotiation and display/input relay; test expiring one-use tokens, disabled/capacity, cleanup and errors.
- [x] Verify red, add guacamole-common dependency and bridge with bounded concurrency/timeouts, run green.

### Task 3: Browser desktop workspace
Files: new api/rdp.js, components/rdp/*, utils/rdp*.js, tests/rdp*.test.js; SshConsole.vue; package.json/lock.
Consumes: connection/session/gateway APIs and guacamole WebSocket contract.
Produces: host/account remote-desktop entry and multi-tab workspace.
- [x] Write lifecycle/input/URL/form tests first and verify red.
- [x] Install pinned guacamole-common-js. Implement CRUD, desktop input/display/fullscreen/fit/reconnect, release all inputs on blur/inactive and close sockets on unmount.
- [x] Run Node tests and production build.

### Task 4: Gateway startup and release packaging
Files: scripts/run-linux.sh, scripts/guacd-helper.sh, packaging scripts/tests, config files, docs/rdp.md.
Consumes: DOUMI_GUACD_HOST/PORT, DOUMI_GUACD_MODE and backend configuration.
Produces: optional owned native/Docker gateway lifecycle and packaged helper.
- [x] Add mocked-process tests for external/default startup, native/Docker ownership, readiness/failure, signal/exit propagation and packaging.
- [x] Verify red, implement optional gateway management without changing default SSH startup; update release/update resources.
- [x] Run Python tests, bash syntax checks.

### Task 5: Integration and review
- [x] Run all Maven tests on Java 17, Node 24 tests/build and Python tests; inspect diff and runtime smoke.
- [x] Request code review; fix findings and reverify impacted tests.
- [x] Commit focused changes on feature branch; retain worktree for user. Report real Windows/guacd validation gaps.

## Verification record

- Java 17 `mvn clean verify` and final `mvn verify`: 353 tests, zero failures/errors, two environment-dependent skips.
- Node 24: 381 tests passed; production build passed (existing chunk-size warning).
- Python release/launcher suite: 14 tests passed, including real-JVM Ctrl-C shutdown and owned gateway cleanup; Bash syntax checks passed.
- Browser smoke with isolated test data and a fake guacd verified profile management, display, keyboard/mouse, fullscreen, reconnect and tabs. This does not establish real Windows RDP compatibility.
- Review findings on JVM signal handling and focus restoration were fixed and rechecked.
- Pinned versions: Java library and optional guacd image 1.6.0; npm guacamole-common-js 1.5.0 (no npm 1.6.0 release available at implementation time).
- Pending external acceptance: real guacd/Windows NLA, certificate handling and Chinese input; Docker runtime and Windows MSI build. No production deployment was performed.
