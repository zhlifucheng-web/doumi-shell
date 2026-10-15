package com.doumiot.init;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库工具类
 * 提供数据库相关的辅助功能
 *
 * @author Lingma
 * @since 2026-02-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseUtil {

    private final DataSource dataSource;

    /**
     * 检查表是否存在
     *
     * @param tableName 表名
     * @return 是否存在
     */
    public boolean tableExists(String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            return tableExists(connection, tableName);
        } catch (SQLException e) {
            log.error("检查表存在性时发生错误: {}", tableName, e);
            return false;
        }
    }

    /**
     * 检查表是否存在（使用现有连接）
     */
    public boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(null, null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    /**
     * 获取数据库中的所有表名
     *
     * @return 表名列表
     */
    public List<String> getAllTableNames() {
        List<String> tableNames = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet tables = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    tableNames.add(tables.getString("TABLE_NAME"));
                }
            }
        } catch (SQLException e) {
            log.error("获取表名列表时发生错误", e);
        }
        return tableNames;
    }

    /**
     * 测试数据库连接
     *
     * @return 连接是否成功
     */
    public boolean testConnection() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(5); // 5秒超时
        } catch (SQLException e) {
            log.error("数据库连接测试失败", e);
            return false;
        }
    }

    /**
     * 获取数据库基本信息
     *
     * @return 数据库信息Map
     */
    public java.util.Map<String, String> getDatabaseInfo() {
        java.util.Map<String, String> info = new java.util.HashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            info.put("databaseProductName", metaData.getDatabaseProductName());
            info.put("databaseProductVersion", metaData.getDatabaseProductVersion());
            info.put("driverName", metaData.getDriverName());
            info.put("driverVersion", metaData.getDriverVersion());
            info.put("url", metaData.getURL());
            info.put("userName", metaData.getUserName());
        } catch (SQLException e) {
            log.error("获取数据库信息时发生错误", e);
            info.put("error", e.getMessage());
        }
        return info;
    }
}