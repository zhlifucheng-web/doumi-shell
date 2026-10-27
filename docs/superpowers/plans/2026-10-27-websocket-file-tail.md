# WebSocket file Tail

Approved: backend continuous reads over `/ws/ssh-console/file-tail`, retaining a dedicated SFTP channel/file per viewer on the existing SSH connection. Historical reads and initial filtering remain bounded HTTP requests.

- [x] Backend reusable reader, exclusive filter cursor ownership, WebSocket subscription lifecycle.
- [x] Frontend push transport, acknowledgements, reconnect/replay and viewer integration.
- [x] Tests for channel reuse, pause/reopen, bounded pending batches, replay, selection and cleanup.
- [x] Full tests/build, independent code review, documentation.

Protocol: start `{type,sessionToken,path,encoding,offset,scanId?}`; ready `{type,tailId}`; data `{type,sequence,offset,totalSize,eof,content?,rows?,limited?}`; ack `{type,sequence}`; pause/resume/close. Reconnect starts with `{type:'resume',sessionToken,tailId,sequence}`. Server retains one unacknowledged batch, replays it until confirmed. Paused resources close after 30 seconds; disconnected resources close immediately and subscription state expires after 60 seconds. File change stops Tail with an explicit reload/re-filter message.

No commit or deployment requested. Existing uncommitted viewer/filter changes must be preserved.

## Verification so far

- Frontend Node 24 full suite: 201 passed, 0 failed (`/tmp/ws-tail-frontend-tests.log`).
- Vite production build passed (`/tmp/ws-tail-build.log`); existing bundle size advisory remains.
- Frontend independent review and scoped re-reviews: clean (`/tmp/ws-tail-frontend-review.md`).
- Client tests cover selection pause, held data, sequence continuity, ACK loss/replay, stale subscription replacement, reconnect during async application, invalid frames, result-limit closure and UTF-8 handoff.
- Backend targeted suite: 40 passed, 0 failures/errors/skips (`/tmp/ws-tail-complete-green.log`), covering 16 WebSocket lifecycle tests and existing scanner/controller/chunk/path regressions.
- Backend independent review: clean after startup metadata, stale-socket pause and unacknowledged filtered-cursor fixes (`/tmp/ws-tail-backend-review.md`).
- Disconnect cleanup waits for the current bounded read-to-pending commit before closing the handle; this preserves decoder/scanner state. Closed/expired filtered subscriptions with an unacknowledged batch invalidate the advanced cursor and require a fresh scan.
- Tests use mocked SSH/SFTP/socket endpoints. No live remote deployment was performed. SFTP v3 cannot reliably detect a same-size replacement preserving mtime and both byte anchors.
- Full backend tests require built frontend files at `shell-end/src/main/resources/static`, matching `scripts/package-release.ps1`; a bare clean checkout otherwise returns 404 for the existing homepage integration test. Copy `shell-front/dist` there after Vite build, then run Java 8 offline Maven tests exclusively against the build directory.
- Final backend full suite: 212 tests, 0 failures, 0 errors, 2 existing GNU-tool prerequisite skips (210 passed), Maven BUILD SUCCESS (`/tmp/ws-tail-backend-final-tests.log`). Earlier parallel runs conflicted in shared generated output; those results were discarded and compilation was cleaned before the exclusive final run.
- Final `git diff --check` passed. Changes remain uncommitted and undeployed.

## 2026-10-27 follow-up authorization and adaptive Tail

The user subsequently authorized commit, push and deployment, including the collapsed find/filter toolbar. Tail now uses a 50ms nonblocking dispatcher with per-subscription due times: active 200ms, idle 400/800/1000ms, reset on newly read bytes. Both normal and filtered backlog reads enqueue a continuation immediately after ACK. Four read workers share a bounded FIFO queue; one queued/running read and one pending data frame per subscription remain the limits. Each task handles one batch before yielding.

SFTP initialization and normal disconnect/reconnect resource cleanup run on workers. An early ready ID allows resume while initialization is paused; a WebSocket disconnect during opening abandons only that handle, preserving the subscription. Pause/close use immediate control flags, and tests release blocked SFTP operations to verify held frames and suppressed late delivery.

The viewer renders cached keyed text blocks instead of replacing the whole content area. Matching caches per block include a keyword-sized lookahead, preserving cross-block highlights without revisiting all old matches. Filtered Tail upserts changed rows in place. There is no batching timer added before ACK; accepted reactive state is acknowledged after the callback completes.

Updated verification and live latency measurements are recorded in release build metadata and the deployment report. Earlier verification notes above describe the initial implementation rather than the final runtime state.
