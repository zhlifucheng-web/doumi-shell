# Terminal directory following and upload ownership implementation plan

> For agentic workers: use subagent-driven-development to implement and review the tasks below.

Goal: Follow the active terminal's actual cwd in the SFTP panel while retaining the original SSH identity, with optional upload ownership assigned to the terminal user.
Architecture: Read-only Linux /proc probes run on an independent short-lived exec channel of the existing connection. tmux supplies the active pane PID; plain SSH is matched by SSH_CONNECTION and SSH_TTY. SFTP identity never changes. A frontend polling composable owns one in-flight request, ignores stale responses, and pauses for inactive/unmounted views. Upload destination and optional numeric uid/gid are captured at enqueue time and fixed in the backend upload record.
Tech stack: Java8/Spring/SSHJ, Python2/3 remote read-only probe, Vue3/Node24.
Spec: User-approved feature description in this chat: directory follows cd/su-/exit, identity remains login, show both users, optional upload ownership, preserve existing attributes on overwrite, distinguish successful upload from ownership failure.

## Global constraints

- No new SSH login, privilege elevation, remote agent installation, interactive command injection, credentials capture, or modifications to remote shell startup files.
- Detect unsupported Linux/Python, unreadable /proc/cwd, deleted directories and nested SSH explicitly; never infer a path from typed commands or a prompt.
- Detect nested shells and su/exit via foreground process groups/ancestry; running editors/tail must not cause directory jumps.
- Non-follow mode retains all existing file operations. Default follow=true stored in browser; manual navigation disables following. Tab/session changes reject old responses.
- Only uid/gid are accepted for optional upload ownership, range validated, applied via SFTP setattr. No chown shell interpolation. Default option off.
- Existing file overwrite preserves uid/gid/mode or fails before replacing the original. Explicit ownership is applied after successful commit and reports a separate warning on failure.
- No unbounded work queues; polling once per 1000ms after completion, no overlap. Stop when inactive/deactivated/unmounted; prevent auto jumps over an open viewer/editor.

## Task 1: Context probe and API (root)

Files: new ssh/SshTerminalContextProbe.java, resources/ssh/terminal-context.py, service/SshTerminalContextService.java, controller/SshTerminalContextController.java; extend SshTmuxSession with contextCommand(); tests for parser, command quoting, and Python process snapshots.
Interface: GET /api/ssh-console/terminal-context?sessionToken=... -> {available,status,message,cwd,terminalUser,terminalUid,terminalGid,fileUser,fileUid,fileGid,contextId}.
- [x] RED tests: parser rejects malformed/relative cwd; probe uses current active tmux PID, nested su shell and exit, idle versus foreground tools; unsupported/inaccessible states never return a guessed cwd.
- [x] Implement bounded remote command using python3 or python, stdin script and explicit base PID/TTY for tmux, or SSH_CONNECTION match for plain SSH; output one bounded JSON document with no secrets/argv.
- [x] GREEN targeted Java tests and real plain SSH probe in disposable shell sessions; target has no tmux, so real tmux remains unverified.

## Task 2: Upload attributes and ownership (backend worker)

Files: SftpStreams.java, SshUploadService.java, SshFolderTransferService.java, SshSftpController.java, relevant Java tests.
Interfaces: optional ownerUid/ownerGid request params on upload/start, upload, folder/upload and folder/mkdir; immutable validated uid/gid snapshot. complete and folder uploads return {uploaded:true,ownershipApplied:boolean,warning:String}; default ownershipApplied=false, warning=null. Old direct Java signatures retained via overloads.
- [x] RED: existing mode/uid/gid restored; restore failure prevents rename and original remains; optional ownership fixed at start and applied after rename; ownership error reports uploaded=true with warning; missing/negative/oversized uid/gid rejected before upload; folder upload files/directories use captured pair.
- [x] Implement SFTP metadata operations only. Preserve requested setuid/setgid bits carefully: chown before final chmod, and do not let post-upload ownership accidentally reintroduce privileged bits. Keep chunk/memory/atomic rename limits.
- [x] GREEN targeted tests; provide log/results. Do not commit, push, deploy or run concurrent Maven without coordination.

## Task 3: Follow UI and upload selection (frontend worker)

Files: new composables/useTerminalFileContext.js and tests, SshFilePanel.vue and setup tests, FolderTransfer.vue, api/sshConsole.js and upload tests; no SshConsole rewrite needed.
Interfaces: API context payload Task1; ownerUid/ownerGid and upload result Task2. API sftpUploadLarge options accept ownerUid/ownerGid, return result. composable injected scheduler for deterministic no-overlap/stale response/lifecycle tests.
- [x] RED: follow cd/user change, toggle off, inactive stop, stale tab/token response, errors displayed without global popup; upload queue captures absolute dir and numeric ownership; warnings appear independently of completed upload.
- [x] Implement single polling loop with generation guard and cleanup. Display original file user and terminal user; manual navigation disables follow; context unavailable keeps current list and clear state. Do not refresh unchanged cwd, and defer cwd jump while viewer/editor is open.
- [x] Forward fixed ownership to file input, drag/drop and folder upload paths. Option disabled when terminal uid/gid unavailable. Never switch SFTP identity or change file tokens. Keep current Ctrl+K provider behavior.
- [x] GREEN Node24 targeted/full tests and Vite build; do not commit/push/deploy.

## Task 4: Integration and review (root)

- [x] Run all frontend and backend tests, inspect diff and request independent review. Frontend: 280 passed; backend: 253 total, 0 failures/errors, 2 skipped; Python: 6 passed. Both independent reviews found no confirmed defects.
- [ ] Real SSH: disposable tmux/plain-shell cwd changes, nested su and exit, same-user prompt detection, root SFTP upload with optional target uid/gid, overwrite preserves attributes, ownership failure warning.
- [ ] Build package with exact static match, commit/push main under existing authorization, deploy with backup and configuration/database/Docker baseline checks.
- [ ] Verify runtime assets/service and report tested platforms and limits honestly.

## Verification status before release

Real plain SSH: exact connection matching, cd with spaces, su nobody, switched-user cd, exit, foreground noninteractive script all passed. Target has no tmux, so tmux adapter has unit coverage only. Permission-denied ownership warnings have service and HTTP contract coverage; a real restricted-user SFTP session is unavailable. Deployment and live HTTPS/SFTP results are recorded with the release artifacts.
