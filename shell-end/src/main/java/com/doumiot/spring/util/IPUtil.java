package com.doumiot.spring.util;

import javax.servlet.http.HttpServletRequest;

/**
 * IP地址工具类
 *
 * @author LiFucheng
 * @version 1.0
 * @since 2022-05-02
 */
public class IPUtil {
    // 未知IP
    private static final String UNKNOWN = "unknown";
    // 本地 IP
    private static final String LOCALHOST_IP = "0:0:0:0:0:0:0:1";
    private static final String LOCALHOST_IP1 = "127.0.0.1";

    /**
     * 获取真实IP地址
     *
     * @param request HTTP请求
     * @return 真实IP地址
     */
    public static String getRemoteHost(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || UNKNOWN.equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || UNKNOWN.equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || UNKNOWN.equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip.equals(LOCALHOST_IP) ? LOCALHOST_IP1 : ip;
    }
}