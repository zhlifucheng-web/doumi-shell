# Windows：Failed to launch JVM

安装完成或双击 **Doumi Shell** 时出现 **Failed to launch JVM**，表示自带的 `Doumi Shell.exe` 未能加载安装目录下的内置 Java 运行时（通常是 `runtime\bin\server\jvm.dll`），程序尚未真正启动。这与业务配置、SQLite 无关。

## 1. 确认安装是否完整

默认按用户安装时，目录一般在：

```text
%LOCALAPPDATA%\Programs\DoumiShell\
```

或你自定义的安装路径。应同时存在：

| 路径 | 说明 |
| --- | --- |
| `Doumi Shell.exe` | 图形启动器 |
| `app\doumi-shell.jar` | 主程序 |
| `runtime\bin\java.exe` | 内置 JRE |
| `runtime\bin\server\jvm.dll` | JVM 本地库（缺失时常见本错误） |

PowerShell 快速检查（把 `$dir` 改成你的安装目录）：

```powershell
$dir = "$env:LOCALAPPDATA\Programs\DoumiShell"
Get-ChildItem "$dir\runtime\bin\server\jvm.dll", "$dir\app\doumi-shell.jar" -ErrorAction SilentlyContinue
```

若 `jvm.dll` 不存在，多为安装被中断、升级残留不完整，或安全软件隔离了 `runtime` 目录。

**仅 `runtime\bin\java.exe` 缺失而 `jvm.dll` 仍在**：常见于 Windows Defender 误删 `java.exe`；`Doumi Shell.exe` 有时仍能启动，但部分快捷方式或旧版本会报 **Failed to launch JVM**。请将安装目录（如 `D:\dev\webshell`）加入排除项后修复安装，或从 `app\Doumi Shell.cfg` 复制一份到与 `Doumi Shell.exe` 同级目录。

**安装根目录缺少 `Doumi Shell.cfg`**（只在 `app\` 下有）：从 `app\Doumi Shell.cfg` 复制到 exe 同级后再试。

## 2. 常见原因与处理

1. **安装路径含中文或特殊字符**（含 Windows 用户名下的中文路径）  
   部分 jpackage 版本对非 ASCII 路径支持不好。请卸载后重装，安装目录选 **`C:\DoumiShell`** 等纯英文短路径；或在「以管理员身份运行」安装时选择 **`C:\Program Files\DoumiShell`**。

2. **杀毒 / Defender 隔离 `jvm.dll`**  
   将安装目录加入排除项，卸载后重新安装 MSI，再检查 `runtime\bin\server\jvm.dll` 是否恢复。

3. **未退出旧版本就升级**  
   托盘右键 **Exit Doumi Shell** 完全退出后再运行新 MSI。仍失败则「设置 → 应用」卸载 Doumi Shell，删除空安装目录后重装。

4. **MSI 损坏或与系统不匹配**  
   从 [Gitee Releases](https://gitee.com/hahadoumi/web-shell/releases) 重新下载，核对同目录下的 `.sha256`；需 **Windows 10/11 64 位**。

## 3. 备用启动（绕过 exe 启动器）

安装目录内若有 **`Start Doumi Shell (Java).cmd`**（新版安装包自带），双击它用 `runtime\bin\java.exe` 直接启动 JAR，多数情况下可绕过 **Failed to launch JVM**。

手动命令（路径按实际修改）：

```powershell
cd "$env:LOCALAPPDATA\Programs\DoumiShell"
.\runtime\bin\java.exe -Ddoumi.desktop=true -Djava.awt.headless=false `
  --add-opens=java.base/java.lang=ALL-UNNAMED `
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED `
  -Dfile.encoding=UTF-8 -jar .\app\doumi-shell.jar
```

浏览器访问 `http://localhost/`（若设置了 `DOUMI_PORT` 则改用对应端口）。

## 4. 不用 MSI，仅用 JAR + 本机 JDK 17

1. 安装 [Microsoft JDK 17](https://learn.microsoft.com/java/openjdk/download)（64 位）。  
2. 解压 Release 中的 `doumi-shell.jar` 与 `run-windows.bat` 到同一目录。  
3. 在该目录执行 `run-windows.bat`，或：

   ```bat
   set DOUMI_PORT=8080
   java -jar doumi-shell.jar
   ```

数据目录默认在 `.\data`，与 MSI 版的 `%LOCALAPPDATA%\DoumiShell\data` 相互独立；迁移需自行复制数据目录与 `master.key`。

## 5. 仍无法解决时

请收集并反馈：

- 安装路径（是否含中文）  
- 上述 `jvm.dll` 是否存在  
- 安全软件名称  
- 从 `docs\logs` 或 `%LOCALAPPDATA%\DoumiShell\data\logs` 中的最新日志（若备用启动成功会有日志）

本地从源码打 MSI 见 [DEVELOPMENT.md](DEVELOPMENT.md)，需 Windows x64 **JDK 17** 与 WiX 3.14。
