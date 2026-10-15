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
