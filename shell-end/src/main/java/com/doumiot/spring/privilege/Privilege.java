package com.doumiot.spring.privilege;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限注解
 * 用于标记需要进行权限控制的类或方法
 *
 * @author http://com.doumiot
 * @since 2022-11-21
 * @version 1.0
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Privilege {
    boolean isPublic() default false;

    /**
     * API 鉴权模式，与 pub_tapis.auth_mode 一致：ANY（满足任一绑定权限）、ALL（须满足全部绑定权限）。
     * 方法级未配置时继承类上的值；均为默认时表示 ANY。
     */
    String authMode() default "";
}