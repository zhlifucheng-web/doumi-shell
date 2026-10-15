package com.doumiot.spring.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.stereotype.Component;

/**
 * Bean获取工具类
 * 提供在非Spring管理的类中获取Spring Bean的便捷方法
 *
 * @author com.doumiot
 * @since 2023-02-27
 */
@Slf4j
@Component
public class GetBeanHelper implements BeanFactoryAware {
    private static BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        GetBeanHelper.beanFactory = beanFactory;
    }

    /**
     * 根据Bean名称获取Bean实例
     *
     * @param beanName Bean名称
     * @return Bean实例
     */
    public static Object getBean(String beanName) {
        return beanFactory.getBean(beanName);
    }

    /**
     * 根据类型获取Bean实例
     *
     * @param clazz Bean类型
     * @param <T>   Bean类型参数
     * @return Bean实例
     */
    public static <T> T getBean(Class<T> clazz) {
        return beanFactory.getBean(clazz);
    }

    /**
     * 根据Bean名称和类型获取Bean实例
     *
     * @param beanName Bean名称
     * @param clazz    Bean类型
     * @param <T>      Bean类型参数
     * @return Bean实例
     */
    public static <T> T getBean(String beanName, Class<T> clazz) {
        try {
            return beanFactory.getBean(beanName, clazz);
        } catch (Exception e) {
            log.error("获取Bean失败: beanName={}, clazz={}", beanName, clazz, e);
        }
        return null;
    }
}