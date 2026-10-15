package com.doumiot.init;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 数据库初始化配置属性
 *
 * @author Lingma
 * @since 2026-02-13
 */
@Data
@ConfigurationProperties(prefix = "app.database.init")
public class DatabaseInitProperties {

    /**
     * 是否启用数据库初始化
     */
    private boolean enabled = true;

    /**
     * SQL脚本文件路径列表
     */
    private List<String> scripts = Collections.singletonList("sql/sqlite_schema.sql");

    /**
     * 是否在初始化失败时停止应用启动
     */
    private boolean failFast = true;

    /**
     * 初始化模式
     * ALWAYS: 总是执行初始化
     * NEVER: 从不执行初始化
     * AUTO: 自动检测是否需要初始化
     */
    private InitMode mode = InitMode.AUTO;

    public enum InitMode {
        ALWAYS, NEVER, AUTO
    }
}
