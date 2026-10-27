# File viewer tools implementation plan

**Goal:** Make file viewers selectable/searchable/copyable, default double-click to viewing, and support whole-file keyword filtering with context and incremental Tail.

**Architecture:** Keep normal chunk viewing. Add a bounded SFTP scan cursor on the server, read one bounded batch per request, and return original numbered lines. A shared viewer component serves the standalone route and existing dialog, with a separate filter composable and text utilities.

**Tech stack:** Vue 3, existing Element Plus, Spring Boot / SSHJ, Java 8-compatible source, Node 24 tests.

**Approved design:** User confirmations in this chat, including Ctrl/Cmd+F interception and automatic Tail at the actual file bottom.

**Follow-up:** The user subsequently approved persistent backend WebSocket Tail. The polling/no-idle-handle notes below describe the original implementation and are superseded for Tail by [WebSocket file Tail](2026-10-27-websocket-file-tail.md). Historical HTTP reads retain their original bounded resource lifecycle.

## Constraints

- Literal keyword, optional case sensitivity, optional two surrounding lines. Search navigates displayed text; filtering scans the whole file.
- Bounded memory, bounded batches, explicit scan progress/cancellation/result limit; no shell commands or whole-file browser download.
- Preserve original line numbers, avoid duplicate context, support encoded/chunked characters and partial last-line appends. Detect truncation and request restart rather than mixing results.
- Tail starts at real EOF / explicit bottom, pauses for upward reading or selection. Filter result bottom alone must not imply source EOF.
- Keep explicit download/edit operations. Double-click binary shows unsupported-preview notice, never auto-downloads.
- Preserve dirty work; do not push or deploy under this implementation-only request.

## Task 1: Bounded backend scan

- [x] Write scanner tests with literal expected line numbers/text for context overlap, page boundaries, UTF-8/GBK, partial lines, limits.
- [x] Run tests RED before implementation.
- [x] Implement scanner and session-owned cursor service with start/next/cancel, idle cleanup and limits. Each next opens/closes its own SFTP resources; no idle remote handles.
- [x] Add controller routes and lifecycle tests for wrong-session cursors, cancellation, truncation and bounded reads.
- [x] Run targeted backend tests GREEN.

## Task 2: Shared viewer and interactions

- [x] Add failing behavior tests for literal highlighting (including HTML special characters), search navigation / key interception, filter cancellation/stale responses, double-click and Tail transitions.
- [x] Implement shared viewer body for standalone/dialog, selectable text, selected/all-displayed copy, search navigation and focus shortcuts. Escape content before mark rendering; never highlight HTML entities as text.
- [x] Add filter composable calling the scan API, explicit progress/status/limit/cancel, result line numbers, context/case controls. Preserve normal view scroll position when leaving filter; filter Tail polls only after historical EOF.
- [x] Change double-click to text viewing and binary notice. Keep directory navigation and explicit editor/download actions.
- [x] Run frontend tests/build GREEN.

## Task 3: Integration verification

- [x] Update user guide for loaded-content search versus whole-file filtering, Tail/selection, limits.
- [x] Run complete frontend suite and production build, backend suite on supported runtime; check diff.
- [x] Independent review of final diff, resolve actionable issues, rerun affected tests.
- [x] Report results and limits. Leave changes for user review without deployment/push.

## Verification evidence

- Frontend: Node 24 full suite 189/189 passed; Vite production build passed (existing bundle-size advisory).
- Backend: Java 8 Maven full suite 196 total, 194 passed, 2 GNU-prerequisite skips; no failures/errors.
- Backend targeted scan/controller/path tests: 24/24 passed.
- Browser fixture smoke: actual text selection and clipboard copied `ERROR`; Ctrl/Cmd+F focused viewer search; 60 literal matches and numbered filter rows rendered. Uses local fixed data, not a production SSH session.
- Independent frontend/backend reviews: no remaining actionable defects.
- No commit, push or deployment in this implementation task.
