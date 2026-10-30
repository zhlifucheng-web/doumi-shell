# Unified RDP Account Connections Implementation Plan

> Execute with subagent-driven-development in the existing codex/windows-rdp worktree. The user approved the unified account UI and requested connection testing. Continue through implementation and verification without another design approval.

**Goal:** Display SSH and RDP as peer account connections, edit them through one account form, open desktop sessions in the main workspace, and test RDP authentication using unsaved form values.

**Architecture:** Preserve independent encrypted RDP storage and existing profiles. Add typed RDP account tree nodes (`RDP_ACCOUNT`, key `r:<id>`, `extra.protocol=RDP`) alongside existing SSH accounts; use typed tab identity to prevent colliding numeric IDs. Reuse the real Guacamole bridge for an isolated, bounded test connection that must observe successful RDP session establishment rather than just TCP or guacd readiness. The browser never receives stored credentials.

**Stack:** Java 17 / Spring / SQLite / Guacamole 1.6.0, Vue 3 / Element Plus / Node 24.

**Spec:** Approved design in this conversation: peer account nodes, protocol selector, main desktop tabs, explicit per-connection ignore-certificate setting, compact actionable errors, test connection.

## Constraints
- Work in the existing feature worktree; preserve all existing SSH behavior, profiles and encrypted credentials.
- No deployment, merge or push in this development turn.
- Avoid unnecessary secondary labels and descriptions. Let the layout do the explaining.
- Ignore certificate is opt-in and applies only to the tested/saved RDP connection; keep NLA and TLS enabled.
- Tests accept unsaved values; editing with a blank password uses the existing password without persisting changes.
- No test success on gateway reachability alone. Cleanup all sockets/workers after success, failure, deadline or shutdown; sanitize errors.

## Tasks
- [x] Backend tree: test peer SSH/RDP nodes, distinct keys, enabled flags, lookup and RDP drag/move/reorder support without breaking old clients; implement RDP lookup and necessary ordering/metadata compatibility.
- [x] Backend connection test: first add fake-guacd tests for successful actual session establishment, authentication/certificate failures, unsaved overrides, blank-password reuse, timeout and cleanup; implement POST `/api/ssh-console/rdp/connections/test` with `{id?,hostId,username,password?,domain,port,ignoreCertificate}` and result `{success,message,elapsedMs}`. Return actionable failure without secrets.
- [x] Unified account form: test payload/protocol/identity behavior; implement reusable account editor supporting both existing SSH account APIs and RDP APIs, test current fields without saving, certificate checkbox visible for RDP, request generation guards, inline test feedback. Editing existing connections locks protocol to prevent destructive conversion.
- [x] Main workspace: integrate RDP nodes and editor, remove old host/account RDP menu and modal, share main tab bar and display RdpDesktop in main content with SSH-only panels/tools hidden. Keep inactive desktop input released and preserve typed tabs across navigation/refresh. Existing RDP profiles appear without migration or credential loss.
- [x] Error recovery: retain allowlisted technical error details behind disclosure, concise translated certificate/auth/network message plus edit/retry actions. Keep explicit ignore-certificate control in account form; editing certificate setting reconnects via the new value.
- [x] Verify: targeted TDD then full Java 17 Maven verify, Node 24 tests/build, launcher/package tests and browser smoke with isolated data. Review each backend/frontend task and whole integration; fix material findings. Commit focused source changes, retain branch/worktree and report real-Windows validation limits.

## Decisions and progress
- Ruling: keep separate SSH/RDP storage and use typed tree/tab identities; this preserves existing SSH APIs and deployed RDP profiles while giving users one account workflow.
- Ruling: protocol is selectable only when creating a connection; changing an existing connection's protocol requires a separate new account, avoiding silent credential conversion.
- Ruling: testing a disabled draft is allowed if its host is enabled; testing does not save or enable the account.

## Verification — 2026-10-30
- Java 17 clean Maven verify: 363 tests, 0 failures/errors, 2 skipped; final JAR rebuilt with the latest frontend.
- Node 24: 415 tests passed; Vite production build passed (existing large-chunk warning).
- Launcher and release packaging: 14 Python tests passed; Linux launcher/helper bash syntax passed.
- Isolated browser smoke with a simulated guacd: peer SSH/RDP nodes, protocol editor, unsaved certificate failure/success without persistence, stored-password reuse, multiple desktop tabs, same-ID SSH/RDP switching, refresh restoration and RDP header/status, edit-from-error recovery and visible footer actions at 720px height verified. No real Windows credentials used.
- Review findings fixed with regressions: AI SSH picker must filter typed desktop nodes; stale new-connection lookups cannot override newer selection or reopen closed tabs; shared header/status remain separate from SSH AI context.
- Production remains at its prior deployment. Real Windows NLA login and target-specific certificate behavior still require validation against a real endpoint.
