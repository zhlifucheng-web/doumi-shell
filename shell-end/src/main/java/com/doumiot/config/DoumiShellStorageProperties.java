package com.doumiot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据目录：SQLite 库文件与可选外置配置均落在该目录下（跨平台路径）。
 */
@Data
@ConfigurationProperties(prefix = "doumi.storage")
public class DoumiShellStorageProperties {

    /**
     * 数据根目录，默认 jar 启动目录下的 {@code data/}。
     * 可通过环境变量 {@code DOUMI_DATA_DIR} 覆盖。
     */
    private String dataDir = "./data";

    private String dbFileName = "doumi.db";
}
