package com.doumiot.common.util;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * MDC工具类，用于在线程间传递MDC上下文信息
 */
public class MDCUtil {

    /**
     * 获取当前线程的MDC上下文
     *
     * @return MDC上下文映射
     */
    public static Map<String, String> getMDCContext() {
        return MDC.getCopyOfContextMap();
    }

    /**
     * 设置MDC上下文
     *
     * @param context MDC上下文映射
     */
    public static void setMDCContext(Map<String, String> context) {
        if (context != null) {
            MDC.setContextMap(context);
        } else {
            MDC.clear();
        }
    }

    /**
     * 包装Runnable，确保在新线程中保持MDC上下文
     *
     * @param runnable 原始Runnable
     * @return 包装后的Runnable
     */
    public static Runnable wrap(Runnable runnable) {
        Map<String, String> context = getMDCContext();
        return () -> {
            setMDCContext(context);
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }

    /**
     * 包装Callable，确保在新线程中保持MDC上下文
     *
     * @param callable 原始Callable
     * @param <T> 返回值类型
     * @return 包装后的Callable
     */
    public static <T> Callable<T> wrap(Callable<T> callable) {
        Map<String, String> context = getMDCContext();
        return () -> {
            setMDCContext(context);
            try {
                return callable.call();
            } finally {
                MDC.clear();
            }
        };
    }
}
