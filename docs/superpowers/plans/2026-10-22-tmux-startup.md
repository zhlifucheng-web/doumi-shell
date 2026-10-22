# tmux startup recovery implementation plan

> Execute the approved design inline with test-driven-development; request an independent code review before completion.

**Goal:** Bound terminal startup, verify the actual tmux client attachment, and stop stale monitoring after disconnect.

**Architecture:** Run short preparation commands on separate SSH channels with deadlines. Attach over a PTY and wait for the exact client identity in tmux before publishing ready. Preserve terminal IDs, sharing permissions, existing tmux sessions, and the pending encoding changes.

**Tech stack:** Java 8 / Spring Boot / SSHJ 0.38; Vue 3 / xterm; Node 24 tests.

**Spec:** Approved six-point design in the current conversation.

## Constraints
- Support tmux 1.6: no `new-session -A` or `-c`.
- Never run global aggressive-resize during startup; never kill existing tmux servers or user sessions.
- No fixed timeout on a healthy interactive terminal. Shared terminals never fall back to an unrelated shell.
- Work in the current user-authorized checkout; preserve pre-existing dirty files and database.

## Task 1: bounded SSH and tmux startup
- [x] Add regression tests: short-command timeout must be an error; failed startup releases resources; no readiness until the exact attached client is observed; failed shared startup cannot become plain SSH.
- [x] Run Maven tests and observe failures before implementation.
- [x] Implement bounded preparation and attachment confirmation, cleanup, and concurrent session creation handling in SshTerminalService / a focused tmux startup helper / SshRemoteExec.
- [x] Run the targeted tests and preserve existing service tests.

## Task 2: terminal lifecycle and monitoring
- [x] Add executable lifecycle tests for error/close, stale connection callbacks, startup timeout and heartbeat timeout.
- [x] Run Node tests and observe the missing behavior.
- [x] Make SshTerminal emit disconnect on failure and reject stale callbacks; clear owner tokens; stop polling invalid sessions and abort stale requests.
- [x] Run frontend tests and production build.

## Task 3: runtime acceptance and review
- [x] Restart only this checkout's backend/frontend, keeping the original data directory.
- [x] Verify actual command results over ten reconnects and five independent sessions on 192.168.78.140.
- [x] Verify shared read/write and read-only connections and inherited encoding.
- [x] Verify timeout/failure cleanup with controlled test doubles; inspect remote process growth without deleting historical processes.
- [x] Request independent code review, resolve findings, and report evidence and remaining limits. Leave services running for review.

## Acceptance evidence
- Targeted backend tests: 28 passed, including terminal startup, timeout, sharing, monitoring and script execution.
- Frontend tests: 60 passed. Production Vite build passed with the existing chunk-size warning.
- Real tmux 1.6 host 192.168.78.140: ten reconnects preserved shell state; five concurrent terminals stayed isolated; resizing, UTF-8 shared input/output, read-only enforcement, explicit plain SSH, shared fallback rejection and guest cleanup passed.
- GBK metadata and browser default selection verified with an encoding-free URL. This does not establish GBK byte compatibility through the UTF-8 tmux transport.
- Independent review: resize concern withdrawn after verifying the actual SSHJ channel type and real resize behavior; no outstanding P1/P2 findings.
- Old aggressive-resize process count remained 39 across the final acceptance interval; historical processes and original user sessions were preserved.
- Local services left running with the original data directory; temporary test share links revoked.
