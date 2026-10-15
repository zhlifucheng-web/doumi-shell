package com.doumiot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 支持 application.yml 外置：jar 同级 {@code config/} 或环境变量 {@code DOUMI_CONFIG_DIR} 指定目录。
 */
@Slf4j
public final class ExternalConfigSupport {

    public static final String ENV_CONFIG_DIR = "DOUMI_CONFIG_DIR";
    private static final String PROP_ADDITIONAL_LOCATION = "spring.config.additional-location";

    private ExternalConfigSupport() {
    }

    public static void register(SpringApplication application) {
        List<String> applied = applyAdditionalLocations();
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
            ConfigurableEnvironment env = event.getEnvironment();
            if (!applied.isEmpty()) {
                log.info("已启用外置配置目录（优先级高于 jar 内 application.yml）：{}", applied);
            }
            logExternalConfigSources(env);
        });
    }

    static List<String> applyAdditionalLocations() {
        Set<String> locations = new LinkedHashSet<>();
        appendDirLocation(locations, System.getenv(ENV_CONFIG_DIR));
        Path localConfig = Paths.get(System.getProperty("user.dir", "."), "config");
        if (Files.isDirectory(localConfig)) {
            appendDirLocation(locations, localConfig.toAbsolutePath().toString());
        }
        if (locations.isEmpty()) {
            return new ArrayList<>();
        }
        String merged = String.join(",", locations);
        mergeSystemProperty(PROP_ADDITIONAL_LOCATION, merged);
        return new ArrayList<>(locations);
    }

    private static void appendDirLocation(Set<String> locations, String dir) {
        if (!StringUtils.hasText(dir)) {
            return;
        }
        locations.add(toFileLocation(dir.trim()));
    }

    private static String toFileLocation(String dir) {
        String normalized = dir.replace('\\', '/');
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        return "file:" + normalized;
    }

    private static void mergeSystemProperty(String key, String value) {
        String existing = System.getProperty(key);
        if (StringUtils.hasText(existing)) {
            System.setProperty(key, existing + "," + value);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void logExternalConfigSources(ConfigurableEnvironment env) {
        for (PropertySource<?> ps : env.getPropertySources()) {
            String name = ps.getName();
            if (name.startsWith("applicationConfig:") && name.contains("file:")) {
                log.info("外置配置文件已加载: {}", name);
            }
        }
    }
}
