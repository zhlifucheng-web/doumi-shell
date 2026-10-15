package com.doumiot.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * @author http://com.doumiot
 * @version 1.0
 * @description: TODO  MP配置
 * @date 2022/11/9 15:47
 */
@Configuration
public class MybatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.SQLITE));
        return interceptor;
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean mybatisSqlSessionFactoryBean = new MybatisSqlSessionFactoryBean();
        //获取mybatis-plus全局配置
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        mybatisSqlSessionFactoryBean.setDataSource(dataSource);
        //mybatisSqlSessionFactoryBean关联设置全局配置
        mybatisSqlSessionFactoryBean.setGlobalConfig(globalConfig);
        SqlSessionFactory sqlSessionFactory = mybatisSqlSessionFactoryBean.getObject();
        sqlSessionFactory.getConfiguration().addInterceptor(mybatisPlusInterceptor());
        return sqlSessionFactory;
    }
}
