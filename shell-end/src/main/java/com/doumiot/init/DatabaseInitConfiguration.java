package com.doumiot.init;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 数据库初始化配置类
 *
 * @author Lingma
 * @since 2026-02-13
 */
@Configuration
@EnableConfigurationProperties(DatabaseInitProperties.class)
public class DatabaseInitConfiguration {
    // 配置类，用于启用@ConfigurationProperties
}