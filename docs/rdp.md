# Windows 远程桌面

Doumi Shell 通过 Linux 上的 Apache Guacamole `guacd` 网关连接 Windows RDP。浏览器连接 Doumi Shell，Doumi Shell 连接 guacd，guacd 连接目标 Windows 的 RDP 端口（默认 3389）。网关需要包含 RDP 支持，目标 Windows 需要启用远程桌面，并允许所用账户登录。

在主机或账户的“远程桌面”入口管理连接，填写独立的 RDP 用户名、密码、域和端口。连接复用主机地址，SSH 账户与端口继续单独使用。远程桌面支持多个标签、适应窗口、全屏、断开/重连及发送 Ctrl-Alt-Delete；当前不提供剪贴板、声音或文件重定向。

默认使用 NLA 并验证目标证书。自签名证书应先配置可信证书；确需忽略时，仅对对应连接启用“忽略证书”。密码由服务端加密保存，修改连接时留空保留原密码。备份数据库时同时保留数据目录的 `master.key`。

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
