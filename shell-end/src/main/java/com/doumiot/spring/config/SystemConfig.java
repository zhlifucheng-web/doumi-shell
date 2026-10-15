package com.doumiot.spring.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 系统配置类
 * 配置Spring Boot支持特殊字符请求、开启AspectJ自动代理等
 *
 * @author LiFucheng
 * @author Elon
 * @since 2021-10-26
 */
@Configuration
@EnableAspectJAutoProxy
public class SystemConfig {

    /**
     * 配置Web服务器工厂，支持在查询参数中使用特殊字符
     *
     * @return ServletWebServerFactory实例
     */
    @Bean
    public ServletWebServerFactory webServerFactory() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        // 允许在查询参数中使用特殊字符，如{}[]等
        factory.addConnectorCustomizers(connector -> {
            connector.setProperty("relaxedQueryChars", "(),/:;<=>?@[\\]{}");
            connector.setProperty("rejectIllegalHeader", "false");
        });
        return factory;
    }
}
