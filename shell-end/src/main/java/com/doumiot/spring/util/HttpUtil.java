package com.doumiot.spring.util;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;

/**
 * HTTP工具类
 *
 * @author com.doumiot
 * @since 2023-02-27
 */
public class HttpUtil {

    /**
     * 获取POST请求数据
     *
     * @param request HTTP请求
     * @return POST数据
     */
    public static String getPostData(HttpServletRequest request) {
        StringBuilder data = new StringBuilder();
        String line;
        BufferedReader reader;
        try {
            reader = request.getReader();
            while (null != (line = reader.readLine())) {
                data.append(line);
            }
        } catch (IOException e) {
            return null;
        }
        return data.toString();
    }
}