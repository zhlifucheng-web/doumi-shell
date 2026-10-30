# Windows / Linux self-update and GitHub releases

Approved scope: GitHub repository `zhlifucheng-web/doumi-shell`; Windows x64 MSI and Linux x64 tar.gz; GitHub Actions build/test/release; configurable GitHub release acceleration prefix such as `https://gh-proxy.com/`.

## Release contract

Only stable `vX.Y.Z` releases are installable. Names are `Doumi.Shell-X.Y.Z.msi`, `doumi-shell-X.Y.Z-linux-x64.tar.gz`, and matching `.sha256` files. Never accept a client-supplied artifact URL or execute upstream scripts. Fetch metadata and assets with time/size limits. A configured HTTP(S) prefix is concatenated with the complete trusted GitHub URL; credentials, queries and fragments in prefixes are rejected. Metadata falls back to the proxy if direct GitHub API access fails. Download assets through the configured prefix. Cache invalidates when settings change.

## Update lifecycle

Packaged installs ship a small dependency-free updater JAR. Development runs report self-update unavailable. The server creates a private update-management token in `data/updates/admin-token`; an environment token can override it. Mutating update/settings calls require this token and reject cross-origin browser requests. The token never appears in API responses, logs, or persistent browser storage. The UI displays platform, progress, error recovery, proxy input, token input and a restart/session warning.

One update job owns an interprocess file lock. Prepare/download/verify while the service stays online. Before shutdown, enter maintenance mode, reject new business requests, drain in-flight requests, and refuse installation while script executions or chunk uploads remain active. Ask the user to finish those jobs rather than terminate them. Recheck immediately before handoff. An independent updater waits for the exact old PID to exit. Only then back up program/configuration and data, install, restart with original JVM/application arguments, data directory, working directory, environment and port, and check both version and a per-job health acknowledgement. On failure stop the candidate, restore program and data, restart old version and persist the result. All job files are private; do not follow symlinks. On Windows copy the updater Java runtime outside the installation before MSI starts; run MSI quietly with explicit existing INSTALLDIR. Linux extracts only regular files/directories with safe relative paths. Preserve external configuration and the original data directory.

The initial version with the updater must be installed manually. Updates interrupt SSH connections. Future database migrations must remain compatible with offline data backup/restore. Managed service/container deployments must not use the standalone updater; report unsupported and retain manual downloads.

## CI and acceptance

Java 17 runtime minimum; Node 24; Python >=3.11. PR/main changes run frontend tests and Maven tests/build. Tags `vX.Y.Z` and manual dispatch build Windows MSI on Windows and Linux x64 archive on Ubuntu. Version comes from the tag or validated dispatch input. Tests must pass; embedded frontend version must equal package version. Upload artifacts, verify all four release assets, publish only after both builds succeed; use built-in GITHUB_TOKEN with contents:write only on publishing job. Manual dispatch builds artifacts without publishing. Do not publish or push as part of local development.

Verify with release/proxy/token/path traversal/checksum/concurrency/lifecycle tests, isolated installer fixtures for success and rollback/data restoration, frontend request tests/build, packaging checks and workflow parsing. Native MSI execution and GitHub-hosted workflow success require their actual environments and must be reported separately.
