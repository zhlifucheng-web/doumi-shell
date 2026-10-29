# 一键更新与 GitHub 自动发布

发布源固定为 [zhlifucheng-web/doumi-shell](https://github.com/zhlifucheng-web/doumi-shell)。支持 Windows x64 MSI 和 Linux x64 独立安装包；首次需手动安装包含 `doumi-updater.jar` 的版本。浏览器所在电脑无需安装更新器。

## 使用一键更新

1. 打开“关于”，检查最新正式版本。
2. 从**部署机器**的数据目录读取 `updates/admin-token`，填入“更新管理密钥”。Windows MSI 默认位置为 `%LOCALAPPDATA%\DoumiShell\data\updates\admin-token`；Linux 默认是安装目录的 `data/updates/admin-token`，设置过 `DOUMI_DATA_DIR` 时以实际目录为准。也可通过 `DOUMI_UPDATE_TOKEN` 设置固定密钥。不要将密钥放入仓库。
3. 按需填写“GitHub 加速代理”，保存后会重新检查版本。留空表示直连，例如填写 `https://gh-proxy.com/` 后会通过 `https://gh-proxy.com/https://github.com/...` 下载包与校验文件。版本检测先直连 GitHub API，失败后尝试加速代理；该服务也需要支持 GitHub API 转发。这里只支持地址前缀模式，不是 HTTP CONNECT/SOCKS 代理配置。不接受带账号密码、查询参数或片段的前缀。
4. 等待文件传输和脚本任务结束，再点击“更新至 v…”。更新会断开 SSH 会话并短暂停止服务。页面会显示进度并在重启期间自动重试连接。

密钥不会出现在接口响应中，也不会保存在浏览器存储中。代理设置持久化在数据目录的 `updates/settings.properties`；页面保存的值优先于 `DOUMI_UPDATE_PROXY`。首次启动也可通过 `DOUMI_UPDATE_PROXY=https://gh-proxy.com/` 设定默认值。

程序先完整下载并校验 SHA-256，准备失败时当前服务继续运行。停止服务后备份旧程序和数据，再安装新版，检查版本号和本次启动标识。失败时恢复旧程序及数据并启动旧版本。Windows 会额外下载并校验当前版本的 MSI，以便恢复 Windows Installer 注册状态；因此历史版本的 MSI 和对应校验文件须保留。Linux 保留原数据目录、密钥和外置配置。启动目录、端口、环境、JVM 参数及程序参数会沿用。

容器、systemd 等托管服务应使用原部署工具更新，并设置 `DOUMI_UPDATE_ENABLED=false`；检测到容器/systemd 环境时也会拒绝独立更新器。直接 HTTPS 监听目前使用手动升级。反向代理部署使用正常的 HTTP 后端，并正确配置可信的转发头，以便服务器识别网页同源请求。

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
