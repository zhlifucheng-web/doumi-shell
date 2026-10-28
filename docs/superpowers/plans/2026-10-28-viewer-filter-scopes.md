# Viewer filter scope and native scan implementation plan

Approved scope: the user-confirmed design in this conversation (2026-10-28).

## Constraints

- One input, three modes: 查找当前内容 (default), 筛选当前内容, 筛选整个文件.
- Whole-file scanning requires explicit mode selection plus start. No start on input, mode change, reopen, or encoding change.
- Current filtering consumes only retained raw cache and normal Tail additions. No additional file reads, scan commands, or implicit Tail subscriptions.
- Preserve original scroll position, literal matching, case sensitivity, context 0/2, partial-line state and bounded previews/results.
- Current-cache eviction must also evict result/match contributions; sliding Tail must not rescan the whole retained cache each batch.
- Fragment line labels never imply absolute file line numbers.
- Remove Python only after replacement tests pass. Native grep capabilities, encoding and process control are explicitly probed; unsupported combinations use SFTP compatibility mode.
- Native command autonomy is distinct from backend demand/ACK. Verify suspend/resume, cancellation and process cleanup separately.
- Preserve ACK/replay, file-change recovery, resource limits, original SSH identity, and verified history-to-Tail boundaries.

## 1. Current-cache filtering

- Add `useViewerLocalFilter.js`: `sync(blocks)`, `configure({keyword,caseSensitive,contextLines})`, `clear()`, refs `rows`, `limited`, `stats`.
- Raw blocks are immutable `{renderKey,offset,length,text}`. Add-only sync scans new characters; eviction repairs only affected boundaries and removes stale contributions.
- Returned rows carry stable local identity, `fragment:true`, bounded `text`, `matched`, `truncated` and local `lineNumber`.
- Integrate local rows into `SshFileViewer.vue` without replacing raw chunks or normal Tail. Mode changes cancel a prior whole-file cursor once.
- Test no I/O/implicit Tail, cross-batch/EOF updates, keyword changes, context dedup, sliding eviction, long lines, scroll restoration, and three-mode state isolation.
- Record cold-cache/configuration and append/eviction timings on 16 x 256 KiB retained text.

## 2. Native whole-file scanning

- Replace Python factory/cursor protocol with probed native command execution and bounded backend parsing.
- Resolve safe structured parameters and real capability combinations before enabling a native path; retain explicit fallback reason.
- Verify snapshot boundaries and scanner state at SFTP Tail handoff, including appends, partial EOF, long lines and rotation.
- Verify pause/cancel and process cleanup; unsafe command/control combinations fall back before scanning.
- Remove the Python script and Python-specific tests after Java/native coverage replaces them.

## Verification and rollout

- Focused red/green tests per part, full frontend and backend checks, independent review.
- Actual SSH tests in disposable directories/sessions, measure time and bytes separately from mock/UI timing.
- Commit and push each verified part, package the final artifact, back up and deploy with existing service/port/configuration/Docker preservation gates.
