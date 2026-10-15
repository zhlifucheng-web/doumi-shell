package com.doumiot.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 内置 SQLite，不依赖 MySQL 等外部库。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DoumiShellStorageProperties.class)
public class DoumiShellSqliteDataSourceConfig {

    @Bean
    @Primary
    public DataSource doumiShellDataSource(DoumiShellStorageProperties storage) throws IOException {
        Path dir = Paths.get(storage.getDataDir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path dbFile = dir.resolve(storage.getDbFileName());
        String jdbcUrl = "jdbc:sqlite:" + dbFile.toString().replace('\\', '/');
        log.info("Doumi Shell SQLite: {}", jdbcUrl);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.addDataSourceProperty("foreign_keys", "true");
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("busy_timeout", "10000");
        config.setPoolName("doumi-sqlite");
        return new HikariDataSource(config);
    }
}
