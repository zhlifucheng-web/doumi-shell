# Docker 部署

需要支持 BuildKit 的 Docker Engine 和 Docker Compose V2（当前版本默认支持）。应用和 guacd 由 Compose 分别管理，不需要在宿主机安装 Java、Node 或 Maven，也不需要向容器挂载 Docker socket。

在源码仓库根目录执行：

```bash
cp deploy/docker.env.example .env
docker compose up -d --build
docker compose ps
```

首次启动会构建应用镜像，并自动拉取本机缺少的 `guacamole/guacd:1.6.0` 镜像。应用包含前端和 Java 17 运行环境，等待 guacd 健康检查通过后启动。构建阶段使用固定的 Node 24.22.2、Maven 3.9.9 和 JDK 17；前端测试随镜像构建运行，后端测试应先由开发或 CI 执行（`mvn -f shell-end/pom.xml verify`），镜像内仅编译打包后端。

默认访问 <http://127.0.0.1:61880>。局域网访问时，在 `.env` 将 `DOUMI_BIND_IP` 改为部署机器的局域网 IP；`DOUMI_HTTP_PORT` 可修改宿主机端口。应用使用容器网络中的 `guacd:4822`，guacd 不映射宿主机端口。已有反向代理可继续转发到应用端口，并保留 WebSocket Upgrade 配置。

修改 `.env` 后运行 `docker compose up -d`。如需通过镜像仓库镜像地址拉取基础镜像或 guacd，可设置示例文件中的 `NODE_IMAGE`、`MAVEN_IMAGE`、`JAVA_IMAGE`、`GUACD_IMAGE`；保留对应版本和 guacd 的 RDP 支持。`DOUMI_IMAGE` 可指定应用镜像标签。镜像拉取失败时，Compose 会保留原始错误输出，可据此处理仓库连通性或认证。

## 数据与更新

SQLite 数据库、账户加密用的 `master.key` 和其他应用数据存入 `doumi-data` 卷（默认项目名为 `doumi-shell`，实际卷名通常为 `doumi-shell_doumi-data`）。`docker compose down` 保留数据卷；恢复数据时应完整保留数据库及 `master.key`。应用以 UID/GID `10001:10001` 运行，首次创建的命名卷会使用镜像中 `/data` 的权限。

Docker 部署关闭应用内覆盖更新。更新源码后执行：

```bash
docker compose up -d --build
docker compose logs --tail=100 web-shell guacd
```

更新会重建应用容器，已有连接需要重新打开；数据卷继续保留。普通重建使用已缓存的 guacd 镜像，无需每次重新拉取。需要主动刷新网关镜像时先运行 `docker pull guacamole/guacd:1.6.0`（配置了镜像地址时换为对应地址）。

本配置用于新部署，不会自动迁移宿主机上已有安装或共享其数据目录。现有安装应在停止写入并备份后，单独安排数据库及 `master.key` 的迁移。
