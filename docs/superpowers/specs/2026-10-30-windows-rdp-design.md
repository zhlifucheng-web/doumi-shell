# Windows RDP design

The user authorized implementation of the previously discussed Guacamole approach in a new branch and worktree. Existing SSH behavior and original checkout must remain intact.

## Architecture
Vue uses guacamole-common-js for browser display/input. A same-origin Spring WebSocket bridge uses Apache guacamole-common to negotiate RDP with an independently deployed guacd. Guacd is an optional Linux native process or Docker service, not a Windows-native component. No Guacamole management webapp or new target-side agent is required.

Dependencies are pinned to guacamole-common 1.6.0, optional guacd image 1.6.0, and npm guacamole-common-js 1.5.0. The npm package is a community distribution of the upstream JavaScript client; version 1.6.0 is unavailable there.

## Connections and UI
RDP profiles belong to existing ssh_host rows but are stored separately in rdp_connection, with independent port (default 3389), name, username, domain, encrypted password, enabled flag and opt-in ignoreCertificate. Host IP is reused; SSH port/accounts and Linux-only tools remain unchanged. Add a remote-desktop entry in host/account actions opening an integrated desktop workspace, with profile CRUD, multiple independent tabs, keyboard/mouse, resolution/fit, fullscreen, Ctrl-Alt-Delete, disconnect/reconnect and actionable errors. No unnecessary secondary labels or descriptions. Input is released when inactive or unfocused. Sessions and event handlers are cleaned up on close/unmount. Clipboard/audio/files are deferred.

## Backend contract
GET/POST /api/ssh-console/rdp/connections (GET requires hostId); PUT/DELETE /connections/{id}. DTO: id,hostId,name,username,domain,port,ignoreCertificate,enabled,hasPassword. Save accepts password; blank update preserves password, create requires one. Secrets never appear in DTO/logs. Java package com.doumiot.sshconsole.rdp. RdpConnectionService.resolve(long id) returns RdpTarget with host,port,username,password,domain,ignoreCertificate; checks profile and host enabled. Delete host/group uses FK cascade. RdpConnectionService owns persistence/controller only; bridge ownership is separate.

POST /api/ssh-console/rdp/sessions accepts connectionId,width,height,dpi; returns token. Tokens are random, one-use, expire after 30 seconds; no passwords or host overrides accepted from browser. WS /ws/ssh-console/rdp?token=... uses guacamole subprotocol, same-origin and Guacamole framing. DELETE /sessions/{token} cancels pending or closes active session. GET /gateway returns enabled,available,message. Configuration doumi.rdp: enabled=true, guacd-host=127.0.0.1, guacd-port=4822, max-sessions=20, idle-timeout-minutes=30. Limit sizes, timeouts, pending/active counts, cleanup on close/error/shutdown. NLA enabled, certificate validation default; opt-in ignore only for explicitly configured profiles.

## Deployment
Linux launcher supports DOUMI_GUACD_MODE=external (default), native, docker. External leaves existing startup unchanged. Native/docker mode starts only the gateway it owns, checks readiness, then launches Java; stops only owned resources on failure/exit, forwards signals and preserves Java exit status. Docker bind is loopback only; image is version-pinned. Windows uses external gateway variables with explicit docs. Include helper/config files in Linux/Windows release packages and update replacement allowlist where required. No firewall modifications, automatic Docker installation or production deployment.

## Acceptance
Unit/integration tests cover encrypted CRUD, password preservation, host/profile enabled checks, field validation, schema initialization against existing DB, one-use/expiry/capacity, Guacamole handshake/frame forwarding/errors/cleanup with fake guacd, frontend lifecycle/input, launch ownership/readiness/exit and packaging. Run full Java 17 Maven suite, Node 24 tests/build and Python release tests. Real Windows NLA/Chinese input/certificate behavior and real guacd smoke are distinct acceptance gates; report honestly if unavailable.
