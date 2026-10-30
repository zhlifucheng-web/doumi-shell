# Windows 远程桌面

Doumi Shell 通过 Linux 上的 Apache Guacamole `guacd` 网关连接 Windows RDP。浏览器连接 Doumi Shell，Doumi Shell 连接 guacd，guacd 连接目标 Windows 的 RDP 端口（默认 3389）。网关需要包含 RDP 支持，目标 Windows 需要启用远程桌面，并允许所用账户登录。

在“新建账户”中选择 RDP，填写 Windows 用户名、密码、域（可选）及独立端口（默认 3389）。SSH 和 RDP 账户并列显示在主机下，点击账户即可在主工作区打开对应连接；已有 RDP 配置会直接显示，无需重新填写。编辑现有账户时保留连接类型，新协议可另建账户。远程桌面支持多个标签、全屏、断开/重连及发送 Ctrl-Alt-Delete。可选择窗口分辨率、1280×720、1600×900、1920×1080、2560×1440、3840×2160，或输入自定义宽高后点击“应用”（宽度 640–3840、4 像素步进，高度 480–2160）。固定分辨率下，窗口变化只会等比例放大或缩小画面，不会改变远程会话分辨率；宽高比不同时居中留边。“原始大小”切换为 1:1 显示。当前不自动读取 Windows 控制台的原有分辨率。不提供剪贴板、声音或文件重定向。

账户表单的“测试连接”使用当前填写的主机、端口、用户名、密码、域和证书选项，无需先保存。编辑时密码留空会使用已保存的密码。测试会通过 guacd 等待 RDP 认证后的桌面建立信号，仅网关端口可达不会报告成功；测试最多等待 15 秒，同时最多运行 4 个。测试不会保存表单或保持桌面会话。失败反馈显示在表单底部，修改配置后应重新测试。

默认使用 NLA 并验证目标证书。自签名证书应先配置可信证书；确需忽略时，在 RDP 账户表单中勾选“忽略服务器证书校验”，仅对该连接生效，保存后重新连接；测试连接也会使用该开关。忽略证书不会关闭 TLS 或 NLA。SSL/TLS 连接失败也可能由协议协商引起，忽略证书不能解决所有 TLS 错误。连接失败时可从桌面区域的“编辑连接”修改配置并重试，技术详情可按需展开。密码由服务端加密保存，修改连接时留空保留原密码。备份数据库时同时保留数据目录的 `master.key`。

## Linux 启动

默认 `DOUMI_GUACD_MODE=external`，启动脚本直接运行 Java，不检测或启动网关。仅使用 SSH 时不需要 guacd 或 Docker；未配置网关时，远程桌面会显示网关不可用。

已有网关时，设置 Doumi Shell 可访问的地址和端口：

```bash
DOUMI_GUACD_HOST=192.168.1.20 DOUMI_GUACD_PORT=4822 ./run-linux.sh
```

安装好包含 RDP 支持的 guacd 后，可让启动脚本管理本机进程：

```bash
DOUMI_GUACD_MODE=native ./run-linux.sh
# guacd 不在 PATH 时：
DOUMI_GUACD_MODE=native DOUMI_GUACD_BIN=/usr/local/sbin/guacd ./run-linux.sh
```

已有可用 Docker 时，可使用固定版本官方镜像：

```bash
DOUMI_GUACD_MODE=docker ./run-linux.sh
```

Docker 模式使用 `guacamole/guacd:1.6.0`，每次创建独立命名的容器，只映射 `127.0.0.1:4822:4822`。镜像需要能从本机缓存或仓库获取。启动脚本不会安装 Docker，也不会修改防火墙。

`native` 和 `docker` 模式要求 `DOUMI_GUACD_HOST=127.0.0.1`；可用 `DOUMI_GUACD_PORT` 更换本机监听端口。端口已占用时启动失败，请改用 `external` 或空闲端口。网关 TCP 就绪等待默认 15 秒，可设置 `DOUMI_GUACD_START_TIMEOUT=1..300`。就绪检测只验证 TCP 监听，实际 RDP 协商仍可能因目标网络、凭据或证书失败。启动失败时不会继续启动 Java；Java 退出后会清理本次创建的网关。TERM、HUP 会转发给 Java；INT（Ctrl-C）会转换为 TERM，让后台 JVM 正常执行关闭钩子。脚本保留 Java 的实际退出码（Ctrl-C 通常为 143）。已有进程和容器不会被接管或停止。一键更新会先等待旧启动脚本清理网关，再通过启动脚本启动新版程序与网关；远程桌面连接会中断，需要重新连接。

## Windows 部署

MSI 包含 Doumi Shell 的 Java 运行环境及配置示例，不包含 Windows 原生 guacd。请在可达的 Linux 机器或 Linux Docker 环境部署 guacd，再设置 Windows 上 Doumi Shell 使用的网关地址：

```bat
set DOUMI_GUACD_HOST=192.168.1.20
set DOUMI_GUACD_PORT=4822
run-windows.bat
```

上例适用于通用 JAR 包的 `run-windows.bat`。使用 MSI 时，请先设置用户环境变量，再从开始菜单启动 Doumi Shell。也可将安装目录 `app/config.example.yml` 复制为独立目录中的 `application.yml`，配置 `doumi.rdp`，并用用户环境变量 `DOUMI_CONFIG_DIR` 指向该目录。环境变量更改后需要重新启动程序。Windows 启动方式不负责 Linux 网关生命周期。

guacd 协议没有面向公网的身份验证或传输加密。外部网关应部署在可信私网，仅允许 Doumi Shell 服务访问；跨不可信网络时使用受保护的网络隧道。不要将 guacd 端口直接暴露到公网。

## 服务端配置

```yaml
doumi:
  rdp:
    enabled: ${DOUMI_RDP_ENABLED:true}
    guacd-host: ${DOUMI_GUACD_HOST:127.0.0.1}
    guacd-port: ${DOUMI_GUACD_PORT:4822}
    max-sessions: 20
    idle-timeout-minutes: 30
```

`enabled=false` 可关闭远程桌面。托管网关时请保留配置示例中的环境变量表达式，使启动脚本监听地址与后端连接地址一致；不要用固定配置或命令行参数覆盖成另一个网关。

连接失败时依次检查 Doumi Shell 到网关、网关到 Windows 的网络，目标 Windows 远程桌面设置、NLA 账户权限、域名和证书。网关不可用不影响现有 SSH 功能。

RDP 配置随数据管理的加密/明文导出与远程备份一起保存；导入时关联到新建的主机。删除主机、分组或执行包含主机的清理会同时删除其 RDP 配置。开源组件许可见 [第三方声明](third-party-notices.md)。

浏览器会按动画帧合并连续鼠标移动，滚轮和按键按下/释放保持顺序传送。服务端为短时输入突发提供有界缓冲，同时限制消息数和总字符数。断线时应用日志记录连接 ID、固定原因码、积压量及持续时间，不记录票据、凭据或输入内容；可据此区分网关错误、输入溢出、写入超时、浏览器关闭与服务重启。
