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

前端在 `shell-front` 中执行 `npm ci`、`npm run dev`，端口为 3100，接口代理到 `http://127.0.0.1:80`，可通过 `DOUMI_BACKEND` 修改代理地址。

后端在 `shell-end` 中执行 `mvn verify`，使用 Java 17 运行生成的 JAR。首次启动初始化完整表结构，不迁移开发阶段的旧数据库。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `DOUMI_PORT` | `80` | 网页、HTTP 和 WebSocket 端口 |
| `DOUMI_DATA_DIR` | `./data`；安装版使用用户目录 | 数据库和主密钥目录 |
| `SSH_CONSOLE_MASTER_KEY` | 自动生成 | 32 字节 Base64 或 64 位 Hex 主密钥 |
| `DOUMI_CONFIG_DIR` | 启动目录 `config/` | 外置 application.yml 所在目录 |

配置键使用 `doumi.storage`、`doumi.ssh-console`。端口、数据目录等示例见 `shell-end/config/application.yml.example`。

Linux 终端默认使用由账户 ID 和终端 ID 确定的 tmux 会话。相同终端 ID 重连会重新附着并保留现场，不同标签使用独立会话。普通终端在目标主机未安装 tmux 或配置 `doumi.ssh-console.tmux-enabled: false` 时使用普通 SSH；此时断开连接会结束交互现场。

## tmux 启动与故障处理

启动分为 SSH 连接、检查、准备、附着四个阶段，通过 WebSocket `connecting` 帧显示进度。短命令在独立 SSH 通道执行：检查和创建命令有 3 秒远端看门狗；附着确认预算为 8 秒，使用客户端 PTY 标识与 `tmux list-clients` 的结果精确匹配后才发送 `ready`。远端看门狗使用 POSIX shell，不依赖 GNU `timeout`。健康的交互会话没有固定存活时限。

兼容命令按 tmux 1.6 验证，不使用 `new-session -A`、`new-session -c`。启动过程不修改全局 `aggressive-resize`。在 192.168.78.140 的 tmux 1.6 上，旧启动链中的 `tmux setw -g aggressive-resize on` 曾阻塞且持续累积；旧实现提前发送 `ready`，导致页面接受输入但 shell 尚未进入 tmux。底层旧版本 tmux 的内部阻塞原因尚未进一步定位。

启动失败会关闭本次 SSH 通道和注册会话。前端启动等待上限为 60 秒；就绪后每 30 秒发心跳，15 秒未收到回应会清理当前连接状态和监控 token。失效 token 停止轮询，切换会话时取消旧监控请求。普通终端失败后可点击“改用普通 SSH”；共享终端必须附着原 tmux，会话缺失时直接报错，避免进入无关 shell。

共享链接携带创建时的编码，服务端也保存编码元数据，因此删除链接的 `encoding` 参数后仍能自动继承。编码是创建时的快照，后续切换不会实时同步。编码选择应与实际远端字节流一致；tmux 的 `-u` 使用 UTF-8，继承 GBK 设置不等同于将 UTF-8 tmux 转为 GBK。140 上中文输入输出按实际 UTF-8 环境验证，GBK 单独验证元数据和页面默认选择。

回归验证：

```bash
mvn -o -f shell-end/pom.xml -Dtest=SshTerminalStartupTest,SshRemoteExecTimeoutTest,SshTmuxSessionTest,SshShareSessionServiceTest,HostMonitorTest,ScriptExecutionTest test
cd shell-front
node --test tests/*.test.js
npm run build
```

2026-10-22 验收：后端相关测试 28 项、前端测试 60 项及生产构建通过；140 实机验证十次重连保留 shell 变量、五个并发独立会话、PTY 缩放、共享读写与只读权限、共享拒绝普通 SSH 回退、普通 SSH 显式连接，以及共享连接数归零。验收保留原有远端 tmux 会话和历史阻塞进程，临时共享链接已撤销。
