package com.doumiot.common.util;

import com.doumiot.common.objects.dto.BaseRequestDTO;
import org.slf4j.Logger;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 通用日志工具类，提供增强的日志打印功能
 */
public class LogUtil {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private LogUtil() {
        // 私有构造函数，防止实例化
    }

    /**
     * 设置MDC上下文信息
     *
     * @param traceId 跟踪ID
     */
    public static void setMDCContext(String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            MDC.put("traceId", traceId);
        } else {
            MDC.remove("traceId");
        }
    }

    /**
     * 设置MDC上下文信息
     *
     * @param requestDTO 基础请求DTO对象
     */
    public static void setMDCContext(BaseRequestDTO requestDTO) {
        if (requestDTO != null) {
            setMDCContext(requestDTO.getTraceId());
            MDC.put("ipAddress", requestDTO.getIpAddress() != null ? requestDTO.getIpAddress() : "unknown");
            // 其他登录相关信息暂不设置，因为BaseRequestDTO中没有这些字段
        }
    }

    /**
     * 清除MDC上下文信息
     */
    public static void clearMDCContext() {
        MDC.clear();
    }

    /**
     * 创建带有上下文信息的日志消息前缀
     * 包含当前时间、线程ID、traceId等信息
     *
     * @param traceId 跟踪ID
     * @return 日志前缀字符串
     */
    public static String buildLogPrefix(String traceId) {
        StringBuilder prefix = new StringBuilder();
        prefix.append("[").append(LocalDateTime.now().format(FORMATTER)).append("]");
        prefix.append("[thread-").append(Thread.currentThread().getId()).append("]");
        if (traceId != null && !traceId.isEmpty()) {
            prefix.append("[traceId:").append(traceId).append("]");
        }
        return prefix.toString();
    }

    /**
     * 创建带有完整BaseRequestDTO信息的日志消息前缀
     *
     * @param requestDTO 基础请求DTO对象
     * @return 日志前缀字符串
     */
    public static String buildLogPrefix(BaseRequestDTO requestDTO) {
        StringBuilder prefix = new StringBuilder(buildLogPrefix(requestDTO != null ? requestDTO.getTraceId() : null));

        if (requestDTO != null) {
            prefix.append("[ip:").append(requestDTO.getIpAddress() != null ? requestDTO.getIpAddress() : "unknown").append("]");
            // 其他登录相关信息暂不包含，因为BaseRequestDTO中没有这些字段
        }

        return prefix.toString();
    }

    /**
     * 使用指定logger打印info级别日志
     *
     * @param logger     Logger实例
     * @param requestDTO 基础请求DTO对象
     * @param message    日志消息
     */
    public static void info(Logger logger, BaseRequestDTO requestDTO, String message) {
        setMDCContext(requestDTO);
        logger.info("{} {}", buildLogPrefix(requestDTO), message);
        clearMDCContext();
    }

    /**
     * 使用指定logger打印error级别日志
     *
     * @param logger     Logger实例
     * @param requestDTO 基础请求DTO对象
     * @param message    日志消息
     */
    public static void error(Logger logger, BaseRequestDTO requestDTO, String message) {
        setMDCContext(requestDTO);
        logger.error("{} {}", buildLogPrefix(requestDTO), message);
        clearMDCContext();
    }

    /**
     * 使用指定logger打印debug级别日志
     *
     * @param logger     Logger实例
     * @param requestDTO 基础请求DTO对象
     * @param message    日志消息
     */
    public static void debug(Logger logger, BaseRequestDTO requestDTO, String message) {
        setMDCContext(requestDTO);
        logger.debug("{} {}", buildLogPrefix(requestDTO), message);
        clearMDCContext();
    }

    /**
     * 使用指定logger打印warn级别日志
     *
     * @param logger     Logger实例
     * @param requestDTO 基础请求DTO对象
     * @param message    日志消息
     */
    public static void warn(Logger logger, BaseRequestDTO requestDTO, String message) {
        setMDCContext(requestDTO);
        logger.warn("{} {}", buildLogPrefix(requestDTO), message);
        clearMDCContext();
    }
}