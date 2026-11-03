# 一键更新与 GitHub 自动发布

发布源固定为 [zhlifucheng-web/doumi-shell](https://github.com/zhlifucheng-web/doumi-shell)。支持 Windows x64 MSI 和 Linux x64 独立安装包；首次需手动安装包含 `doumi-updater.jar` 的版本。浏览器所在电脑无需安装更新器。

## 使用一键更新

1. 打开“关于”，检查最新正式版本。
2. 默认免密授权，不显示“更新管理密钥”输入框。若部署端设置了 `DOUMI_UPDATE_REQUIRE_TOKEN=true`，从**部署机器**的数据目录读取 `updates/admin-token` 并填入；Windows MSI 默认位置为 `%LOCALAPPDATA%\DoumiShell\data\updates\admin-token`，Linux 默认是安装目录的 `data/updates/admin-token`，设置过 `DOUMI_DATA_DIR` 时以实际目录为准。也可通过 `DOUMI_UPDATE_TOKEN` 设置固定密钥。不要将密钥放入仓库。
3. 按需填写“GitHub 加速代理”，保存后会重新检查版本。留空表示直连，例如填写 `https://gh-proxy.com/` 后会通过 `https://gh-proxy.com/https://github.com/...` 下载包与校验文件。版本检测先直连 GitHub API，失败后尝试加速代理；该服务也需要支持 GitHub API 转发。这里只支持地址前缀模式，不是 HTTP CONNECT/SOCKS 代理配置。不接受带账号密码、查询参数或片段的前缀。
   如需使用普通 HTTP 代理，在独立的“HTTP 下载代理”填写 `http://代理主机:端口`，例如 `http://127.0.0.1:7890`；这里的地址须能从**部署机器**访问。该代理用于安装包、SHA-256 校验文件及 Windows 回滚包，HTTPS 使用 CONNECT 隧道并保留证书校验，不转发 GitHub API Token。不支持 SOCKS、代理账号密码或 `https://` 代理端点。可以与 GitHub 加速前缀同时使用。
4. 等待文件传输和脚本任务结束，再点击“更新至 v…”。更新会断开 SSH 会话并短暂停止服务。页面会显示进度并在重启期间自动重试连接。

密钥不会出现在接口响应中，也不会保存在浏览器存储中。代理设置持久化在数据目录的 `updates/settings.properties`；页面保存的值优先于 `DOUMI_UPDATE_PROXY`。首次启动也可通过 `DOUMI_UPDATE_PROXY=https://gh-proxy.com/` 设定默认值。

HTTP 下载代理使用 `doumi.update.http-proxy` 或环境变量 `DOUMI_UPDATE_HTTP_PROXY`，默认留空直连；页面保存的值优先，清空后禁用环境变量默认值。GitHub API 查询不使用此下载代理。读取超时、连接中断、文件截断以及 HTTP 408/429/502/503/504 最多尝试 3 次，每次清理未完成文件后重新下载；仍失败时显示具体下载阶段及节点。HTTP 404、TLS 证书错误、非法重定向和超出大小限制不会自动重试，SHA-256 校验始终保留。

管理密钥校验由部署配置 `doumi.update.require-token`（环境变量 `DOUMI_UPDATE_REQUIRE_TOKEN`）控制，默认 `false`。免密模式下，能访问 Doumi Shell 的用户即可保存更新设置、清除 GitHub Token 及安装更新；同源校验和安装确认仍保留。设为 `true` 并重启后恢复密钥验证；仅设置 `DOUMI_UPDATE_TOKEN` 不会自动开启验证。关闭时不会创建或读取管理密钥文件，已有文件保留，以便后续重新开启。

### GitHub API Token

GitHub 未认证 API 请求通常限制为每个出口 IP 每小时 60 次；配置个人访问 Token 后，通常可提高到每个用户每小时 5,000 次，实际额度以 GitHub 返回值为准。在“关于 → 一键更新”填写可选的“GitHub API Token”后点击“保存更新设置”；启用了管理密钥验证时需同时填写管理密钥。也可在部署端配置环境变量 `DOUMI_GITHUB_TOKEN`，或外置配置 `doumi.update.github-token`。公开仓库版本查询使用最小只读权限即可，无需授予写权限。

页面保存的 Token 复用现有凭据主密钥加密，存储在 `updates/settings.properties`，优先于环境变量。页面只返回“已配置”状态；输入框留空保存时保留原 Token，点击“清除 Token”后禁用认证（包括环境变量的默认值）。两个密钥用途不同：更新管理密钥用于授权本机配置和安装操作，GitHub Token 用于提高 GitHub API 查询额度。

Token 只随直连 `api.github.com` 的版本查询发送，不发送给加速代理、安装包下载地址或重定向目标。直连失败后，代理查询仍使用匿名请求，因此不会获得该 Token 的认证额度。无效或过期 Token 会提示替换或清除。Token 不会回显、写入浏览器存储或包含在更新任务状态中；不要将真实 Token 写入仓库。它提高查询限额，但不能解决代理地址被错误解析或证书过期的问题。

程序先完整下载并校验 SHA-256，准备失败时当前服务继续运行。停止服务后备份旧程序和数据，再安装新版，检查版本号和本次启动标识。失败时恢复旧程序及数据并启动旧版本。Windows 会额外下载并校验当前版本的 MSI，以便恢复 Windows Installer 注册状态；因此历史版本的 MSI 和对应校验文件须保留。Linux 保留原数据目录、密钥和外置配置。启动目录、端口、环境、JVM 参数及程序参数会沿用。

### systemd 部署

Linux x64 的 root 系统服务支持一键更新，包括没有 `INVOCATION_ID` 的 systemd 219。程序从自身 cgroup 识别所属服务，并核对 root 权限、MainPID、安装工作目录及 `KillMode=control-group` 或 `mixed`；部署机器须有 `systemctl` 和 `systemd-run`。网页不能指定服务名或执行命令。不满足条件的托管服务仍提示使用原部署工具升级，也可通过 `DOUMI_UPDATE_ENABLED=false` 主动关闭。

下载、SHA-256 校验及请求排空完成后，独立的临时 `doumi-update-<任务ID>.service` 执行更新，不会随原服务的 cgroup 一起退出。更新器停止原服务后，只备份/替换发布包中的程序文件，并备份完整业务数据；保留 `service.env`、nginx、运行时和历史发布目录。启动与回滚都使用原 systemd 服务，沿用既有端口、环境文件、启动脚本及 guacd 管理。

每次启动通过 `/run/systemd/system/<原服务>.d/90-doumi-update-health.conf` 临时注入本次健康标识；更新或回滚完成后删除该文件并重新加载 unit。不会覆盖持久 unit 或其他 drop-in。健康检查失败时先停止候选服务，再恢复旧程序和数据。若服务无法确认停止或临时配置无法清理，任务标记为 `ROLLBACK_FAILED`，保留备份并阻止再次安装，需到部署机器检查。旧版程序也通过既有环境健康标识参与回滚检查。

容器、非 root 服务、systemd 用户服务及直接 HTTPS 监听目前使用原部署方式升级；反向代理部署使用正常的 HTTP 后端，并正确配置可信的转发头，以便服务器识别网页同源请求。systemd 自动更新能力需要已安装的程序及独立更新器均包含此适配；安装旧的历史 Release 后，后续功能以该 Release 的程序为准。

## 日志、备份与恢复

每次更新保存在数据目录 `updates/<任务ID>/`，包含 `state.properties`、`prepare.log`（准备失败时）、`installer.log`、启动日志及 `backup/program`、`backup/data`。Windows 另有 MSI 安装/回滚日志。备份包含数据库和凭据密钥，请按业务数据保管。确认新版本稳定后可以在服务正常且没有更新任务运行时清理历史任务目录，保留当前任务和所需回滚备份。

若页面显示“需要在部署机器检查”或服务长时间未恢复，先检查是否仍有更新器/MSI/应用进程运行，再查看该任务的日志；不要在安装器运行时替换文件。确认安装器已退出后，可从程序及数据备份恢复，保留 `updates` 目录，并使用原启动方式启动。Windows 如需恢复 MSI 注册信息，使用已校验的旧版 MSI 完成恢复。`ROLLBACK_FAILED` 会禁止再次自动更新；人工确认恢复完成后，备份并移走 `updates/current.properties`，才能开始新的自动更新。

## GitHub Actions

- `Test and build`：`main`/`master` 推送、PR、手动触发。运行前端测试/构建、后端测试/构建及打包检查，提供 Linux 验证包。
- `Release packages`：推送 `vX.Y.Z` 标签触发 Windows 与 Linux 构建。也支持从 Actions 页面手动输入版本号，仅生成工作流附件，不发布 Release。
- 两个平台都成功后，发布任务校验四个文件，先创建草稿并上传，确认远端附件完整后才公开为最新正式版本。构建或上传失败不会把不完整的新版本公开给更新器。
- 版本号从标签或手动输入取得，构建时写入前端 `version.json`，并检查 JAR、MSI/压缩包的版本一致。Windows MSI 限制前三段最大值为 `255.255.65535`。
- 使用 Node 24、JDK 17、Python 3.12。构建需 JDK 17；为兼容现有 Spring 的类扫描器，输出保持旧类文件格式，运行仍需 Java 17。
- 发布只使用当前仓库的 `GITHUB_TOKEN`，仅发布任务授予 `contents: write`。不需要个人 Token，仓库/组织须允许 Actions 发布 Release。
- Windows 构建使用 jpackage、WiX 3.14.1，并执行现有 MSI 目录迁移、快捷方式和安装选项检查。MSI 实际安装与自动回滚还应在 Windows 验收机验证。

正式发布前，将待发布提交推送到 GitHub，再对该提交创建并推送新的 `vX.Y.Z` 标签即可。不要复用已经公开的版本标签；发布脚本拒绝覆盖公开 Release。构建失败后可重跑原标签的工作流，补全尚未公开的草稿。

约定的 Release 附件：

- `Doumi.Shell-X.Y.Z.msi`
- `Doumi.Shell-X.Y.Z.msi.sha256`
- `doumi-shell-X.Y.Z-linux-x64.tar.gz`
- `doumi-shell-X.Y.Z-linux-x64.tar.gz.sha256`
