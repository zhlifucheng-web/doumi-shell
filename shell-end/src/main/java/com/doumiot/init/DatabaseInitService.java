package com.doumiot.init;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库初始化服务
 * 提供更灵活的数据库初始化功能
 *
 * @author Lingma
 * @since 2026-02-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseInitService {

    private final DataSource dataSource;
    private final DatabaseInitProperties initProperties;

    /**
     * 应用启动完成后执行数据库初始化（优先于 Blade 等依赖表结构的监听器，见 {@code @Order}）。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void initializeDatabaseOnStartup() {
        if (!initProperties.isEnabled()) {
            log.info("数据库初始化已禁用");
            return;
        }

        log.info("开始执行数据库初始化...");

        try {
            switch (initProperties.getMode()) {
                case ALWAYS:
                    executeInitialization();
                    break;
                case AUTO:
                    if (shouldInitialize()) {
                        executeInitialization();
                    } else {
                        log.info("数据库已初始化，跳过初始化过程");
                    }
                    break;
                case NEVER:
                    log.info("初始化模式为NEVER，跳过数据库初始化");
                    break;
            }

            log.info("数据库初始化完成");
        } catch (Exception e) {
            log.error("数据库初始化失败", e);
            if (initProperties.isFailFast()) {
                throw new RuntimeException("数据库初始化失败，应用启动终止", e);
            }
        }
    }

    /**
     * 判断是否需要执行初始化
     */
    private boolean shouldInitialize() {
        try (Connection connection = dataSource.getConnection()) {
            // 检查关键表是否存在
            return !tableExists(connection, "ssh_group");
        } catch (SQLException e) {
            log.warn("检查表存在性时发生错误", e);
            return true; // 出错时默认执行初始化
        }
    }

    /**
     * 检查表是否存在
     */
    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(null, null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    /**
     * 执行初始化
     */
    private void executeInitialization() throws Exception {
        for (String scriptPath : initProperties.getScripts()) {
            executeSqlScript(scriptPath);
        }
    }

    /**
     * 执行SQL脚本文件
     */
    public void executeSqlScript(String scriptPath) throws Exception {
        log.info("开始执行SQL脚本: {}", scriptPath);

        List<String> sqlStatements = parseSqlScript(scriptPath);

        if (sqlStatements.isEmpty()) {
            log.warn("SQL脚本文件为空或解析失败: {}", scriptPath);
            return;
        }

        Connection connection = null;
        Statement statement = null;

        try {
            connection = dataSource.getConnection();
            // 关闭自动提交以便事务控制
            connection.setAutoCommit(false);
            statement = connection.createStatement();

            int executedCount = 0;
            for (String sql : sqlStatements) {
                if (StrUtil.isNotBlank(sql)) {
                    log.debug("准备执行SQL: {}", sql);
                    try {
                        statement.execute(sql);
                        executedCount++;
                        log.info("SQL执行成功: {}", truncateSql(sql));
                    } catch (SQLException e) {
                        log.error("SQL执行失败: {}", sql, e);
                        throw e;
                    }
                }
            }

            connection.commit();
            log.info("SQL脚本执行完成，共执行 {} 条语句", executedCount);

        } catch (SQLException e) {
            if (connection != null) {
                try {
                    connection.rollback();
                    log.info("事务已回滚");
                } catch (SQLException rollbackEx) {
                    log.error("事务回滚失败", rollbackEx);
                }
            }
            log.error("执行SQL脚本时发生错误", e);
            throw e;
        } finally {
            closeResources(statement, connection);
        }
    }

    /**
     * 解析SQL脚本文件
     */
    private List<String> parseSqlScript(String scriptPath) throws Exception {
        List<String> sqlStatements = new ArrayList<>();

        // 首先尝试从classpath加载
        ClassPathResource classPathResource = new ClassPathResource(scriptPath);

        if (classPathResource.exists()) {
            log.debug("从classpath加载SQL脚本: {}", scriptPath);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(classPathResource.getInputStream(), StandardCharsets.UTF_8))) {
                return parseSqlFromReader(reader);
            }
        } else {
            // 如果classpath中不存在，则尝试从文件系统加载
            String projectRoot = System.getProperty("user.dir");
            String fullPath = projectRoot + "/" + scriptPath;
            java.io.File file = new java.io.File(fullPath);

            if (!file.exists()) {
                // 尝试项目根目录的上一级目录
                fullPath = projectRoot + "/../" + scriptPath;
                file = new java.io.File(fullPath);
            }

            if (file.exists()) {
                log.debug("从文件系统加载SQL脚本: {}", file.getAbsolutePath());
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
                    return parseSqlFromReader(reader);
                }
            } else {
                log.warn("SQL脚本文件不存在: {} (尝试路径: {})", scriptPath, file.getAbsolutePath());
                return sqlStatements;
            }
        }
    }

    /**
     * 从BufferedReader解析SQL语句
     */
    private List<String> parseSqlFromReader(BufferedReader reader) throws Exception {
        List<String> sqlStatements = new ArrayList<>();
        StringBuilder currentSql = new StringBuilder();

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();

            // 跳过空行和注释行
            if (line.isEmpty() || line.startsWith("--")) {
                continue;
            }

            // 处理多行注释
            if (line.startsWith("/*")) {
                // 跳过多行注释直到结束
                while (!(line.contains("*/") || (line = reader.readLine()) == null)) {
                    line = line.trim();
                }
                continue;
            }

            currentSql.append(line).append(" ");

            // 如果是语句结束符，则添加到语句列表
            if (line.endsWith(";")) {
                String sql = currentSql.toString().trim();
                // 移除结尾的分号
                if (sql.endsWith(";")) {
                    sql = sql.substring(0, sql.length() - 1).trim();
                }

                if (StrUtil.isNotBlank(sql)) {
                    sqlStatements.add(sql);
                }

                currentSql = new StringBuilder();
            }
        }

        // 处理最后可能剩余的SQL语句
        String remainingSql = currentSql.toString().trim();
        if (StrUtil.isNotBlank(remainingSql) && !remainingSql.endsWith(";")) {
            sqlStatements.add(remainingSql);
        }

        log.info("解析SQL脚本完成，共解析出 {} 条语句", sqlStatements.size());
        return sqlStatements;
    }

    /**
     * 截断SQL语句用于日志显示
     */
    private String truncateSql(String sql) {
        if (sql.length() <= 100) {
            return sql;
        }
        return sql.substring(0, 100) + "...";
    }

    /**
     * 关闭数据库资源
     */
    private void closeResources(Statement statement, Connection connection) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                log.warn("关闭Statement时发生错误", e);
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("关闭Connection时发生错误", e);
            }
        }
    }

    /**
     * 手动触发数据库初始化（可用于测试或手动执行）
     */
    public void manualInitialize() {
        try {
            log.info("手动触发数据库初始化");
            executeInitialization();
            log.info("手动数据库初始化完成");
        } catch (Exception e) {
            log.error("手动数据库初始化失败", e);
            throw new RuntimeException("手动数据库初始化失败", e);
        }
    }
}
