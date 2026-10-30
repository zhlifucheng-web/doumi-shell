# syntax=docker/dockerfile:1
ARG NODE_IMAGE=node:24.22.2-bookworm-slim
ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17
ARG JAVA_IMAGE=eclipse-temurin:17-jre-jammy

FROM ${NODE_IMAGE} AS frontend
WORKDIR /build/shell-front
COPY shell-front/package.json shell-front/package-lock.json ./
RUN npm ci
COPY shell-front/ ./
COPY docs/ /build/docs/
RUN npm test && npm run build

FROM ${MAVEN_IMAGE} AS backend
WORKDIR /build/shell-end
COPY shell-end/pom.xml ./
COPY shell-end/src/ ./src/
COPY --from=frontend /build/shell-front/dist/ ./src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode -e -DskipTests package

FROM ${JAVA_IMAGE} AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 doumi \
    && useradd --uid 10001 --gid doumi --home-dir /app --no-create-home doumi \
    && mkdir -p /app /data \
    && chown doumi:doumi /data
WORKDIR /app
COPY --from=backend /build/shell-end/target/shell-end-1.0.0-SNAPSHOT.jar /app/doumi-shell.jar
COPY LICENSE README.md /app/
COPY docs/ /app/docs/
ENV DOUMI_PORT=8080 \
    DOUMI_DATA_DIR=/data \
    DOUMI_GUACD_HOST=guacd \
    DOUMI_GUACD_PORT=4822 \
    DOUMI_UPDATE_ENABLED=false
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS "http://127.0.0.1:${DOUMI_PORT}/" > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/doumi-shell.jar"]
