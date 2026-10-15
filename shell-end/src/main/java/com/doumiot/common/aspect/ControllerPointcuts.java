package com.doumiot.common.aspect;

import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Controller层切点定义集合
 * 倒数第二优先级
 */
@Component
@Order(Integer.MAX_VALUE - 1)
public class ControllerPointcuts {


    /**
     * 定义RequestMapping相关注解切点
     */
    @Pointcut("@annotation(org.springframework.web.bind.annotation.RequestMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PostMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.GetMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PutMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.DeleteMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PatchMapping)")
    public void requestMappingAnnotatedMethods() {
    }
}