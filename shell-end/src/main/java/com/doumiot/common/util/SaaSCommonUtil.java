package com.doumiot.common.util;

import java.util.Objects;

/**
 * 一些通用的工具方法
 */
public class SaaSCommonUtil {
    /**
     * 判断Long是否为空
     *
     * @param value 待判断的Long
     * @return true:为空
     */
    public static boolean isLongEmpty(Long value) {
        return value == null || Objects.equals(value, 0L);
    }

    /**
     * 判断Integer是否为空
     *
     * @param value 待判断的Integer
     * @return true:为空
     */
    public static boolean isIntegerEmpty(Integer value) {
        return value == null || Objects.equals(value, 0);
    }
}
