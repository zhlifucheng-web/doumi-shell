# 开发与构建

项目 Java 包为 `com.doumiot`，后端使用 Java 17、Spring Boot 和 SQLite；前端使用 Vue 3、Element Plus 和 Vite。

## 构建 Windows 安装包

构建电脑需要 Node.js 22+、Maven 3.6+、Windows x64 JDK 17 和 WiX Toolset 3.14。

```powershell
./scripts/prepare-build-tools.ps1
./scripts/package-msi.ps1 -JdkHome 'D:\tools\jdk-17' -WixHome 'D:\tools\wix3'
```

脚本会构建前端、运行后端测试并生成 MSI。版本统一读取 `shell-front/public/version.json`；可用 `-Version x.y.z` 指定版本。`-SkipBuild` 复用 `release/doumi-shell.jar`，但其网页版本必须与 MSI 一致。临时目录默认 `.build`，可用 `DOUMI_BUILD_DIR` 指定。

仅构建 JAR：`./scripts/package-release.ps1`。输出包含 `doumi-shell.jar`、Windows/Linux 启动脚本和配置示例；运行需要 Java 17。Linux 监听 80 端口需要相应权限。

## 本地开发

前端在 `shell-front` 中执行 `npm ci`、`npm run dev`，端口为 3100，API 与 WebSocket 默认代理到远端 `http://10.20.42.116:9081`，可通过 `DOUMI_BACKEND` 修改代理地址。连接本地后端时可运行 `DOUMI_BACKEND=http://127.0.0.1:8080 npm run dev`。

后端在 `shell-end` 中执行 `mvn verify`，使用 Java 17 运行生成的 JAR。首次启动初始化完整表结构，不迁移开发阶段的旧数据库。

目录扫描面向 GNU/Linux。两个真实扫描测试会先检测本机是否具备 Bash、GNU timeout/du/find/sort，以及支持 NUL 分隔记录的 awk；缺少这些工具时明确跳过，解析器等其他测试照常运行。macOS 自带的 BSD 工具不满足条件，发布前应在具备上述工具的 Linux 环境运行这两个测试，无需排除测试方法。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `DOUMI_PORT` | `80` | 网页、HTTP 和 WebSocket 端口 |
| `DOUMI_DATA_DIR` | `./data`；安装版使用用户目录 | 数据库和主密钥目录 |
| `SSH_CONSOLE_MASTER_KEY` | 自动生成 | 32 字节 Base64 或 64 位 Hex 主密钥 |
| `DOUMI_CONFIG_DIR` | 启动目录 `config/` | 外置 application.yml 所在目录 |

配置键使用 `doumi.storage`、`doumi.ssh-console`。端口、数据目录等示例见 `shell-end/config/application.yml.example`。

Linux 终端默认使用由账户 ID 和终端 ID 确定的 tmux 会话。相同终端 ID 重连会重新附着并保留现场，不同标签使用独立会话。普通终端在目标主机未安装 tmux 或配置 `doumi.ssh-console.tmux-enabled: false` 时使用普通 SSH；此时断开连接会结束交互现场。

终端工具栏和分屏标题栏提供“断开连接”：关闭该标签的连接并停止监控，保留标签、当前屏幕和 SFTP 路径，不删除远端 tmux 会话。断开后点击“重新连接”会沿用原终端 ID；手动断开的标签在当前页面切换或重新挂载分屏时不会自动连接。普通 SSH 仍遵循上述断开后结束交互现场的行为。

## tmux 启动与故障处理

终端滚轮使用 tmux 自身的历史缓冲区。准备新会话和重连已有会话时，按会话设置 `mouse on`，旧版回退到当前窗口的 `mode-mouse on`，不修改全局选项。前端同时转发 xterm 的 `onData` 与 `onBinary`，鼠标协议字节绕过 GBK 等正文编码转换。

tmux 兼容探测须保留 SSH stderr 管道，不可把 tmux 的错误输出重定向到 `/dev/null`：实机 tmux 1.6 在此场景下会挂住，导致旧版选项回退无法执行并最终启动超时。

新版 tmux 按 `alternate_on`、`mouse_any_flag` 与 `pane_in_mode` 区分历史滚动和编辑器输入：编辑器未开启鼠标时将滚轮转为方向键，原生鼠标和历史复制模式保留鼠标事件。tmux 1.6 缺少这些字段，Linux 下通过前台进程组识别 vim、nano、emacs、less 等常见全屏工具；普通前台命令仍滚动历史。未知工具或状态查询失败时保留鼠标事件。状态查询使用独立 SSH 通道和短命令超时，连续滚动缓存 250ms，键盘输入立即使缓存失效。该旧版识别不保证覆盖自定义名称的编辑器。

启动分为 SSH 连接、检查、准备、附着四个阶段，通过 WebSocket `connecting` 帧显示进度。短命令在独立 SSH 通道执行：检查和创建命令有 3 秒远端看门狗；附着确认预算为 8 秒，使用客户端 PTY 标识与 `tmux list-clients` 的结果精确匹配后才发送 `ready`。远端看门狗使用 POSIX shell，不依赖 GNU `timeout`。健康的交互会话没有固定存活时限。

兼容命令按 tmux 1.6 验证，不使用 `new-session -A`、`new-session -c`。启动过程不修改全局 `aggressive-resize`。在 192.168.78.140 的 tmux 1.6 上，旧启动链中的 `tmux setw -g aggressive-resize on` 曾阻塞且持续累积；旧实现提前发送 `ready`，导致页面接受输入但 shell 尚未进入 tmux。底层旧版本 tmux 的内部阻塞原因尚未进一步定位。

启动失败会关闭本次 SSH 通道和注册会话。前端启动等待上限为 60 秒；就绪后每 30 秒发心跳，15 秒未收到回应会清理当前连接状态和监控 token。失效 token 停止轮询，切换会话时取消旧监控请求。普通终端失败后可点击“改用普通 SSH”；共享终端必须附着原 tmux，会话缺失时直接报错，避免进入无关 shell。

共享链接携带创建时的编码，服务端也保存编码元数据，因此删除链接的 `encoding` 参数后仍能自动继承。编码是创建时的快照，后续切换不会实时同步。连接握手会携带观看者当前选择的编码；没有传入时采用共享记录中的编码。

GBK 等传统编码的正文可能与 tmux 自己绘制的 UTF-8 边框、空白填充混用。此时不能把整条输出改为 UTF-8，也不能对正文做字符替换。UTF-8 客户端继续使用 `tmux -u`；其他编码只对本次 attach 客户端设置 `LC_ALL=C LANG=C` 并移除 `-u`，让 tmux 使用兼容的装饰绘制方式。已有 pane 的字节、locale 和全局配置保持不变，浏览器仍按选择的 GBK 等编码解码。切换编码会重新附着原 tmux 会话，使客户端绘制模式同步更新；普通 SSH 不会因此重建 shell。

回归验证：

```bash
mvn -o -f shell-end/pom.xml -Dtest=SshTerminalStartupTest,SshRemoteExecTimeoutTest,SshTmuxSessionTest,SshShareSessionServiceTest,HostMonitorTest,ScriptExecutionTest test
cd shell-front
node --test tests/*.test.js
npm run build
```

2026-10-22 验收：后端相关测试 28 项、前端测试 60 项及生产构建通过；140 实机验证十次重连保留 shell 变量、五个并发独立会话、PTY 缩放、共享读写与只读权限、共享拒绝普通 SSH 回退、普通 SSH 显式连接，以及共享连接数归零。验收保留原有远端 tmux 会话和历史阻塞进程，临时共享链接已撤销。
