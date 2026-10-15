package com.doumiot.spring.component;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * 通用Bean配置类
 * 提供项目中常用的Bean实例
 *
 * @author com.doumiot
 * @since 2023-02-27
 */
@Component
public class CommonBeans {

    /**
     * 配置Gson Bean，支持复杂Map键序列化
     *
     * @return Gson实例
     */
    @Bean
    public Gson gson() {
        return new GsonBuilder()
                .enableComplexMapKeySerialization()
                .create();
    }
}
