package com.doumiot.spring.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import javax.servlet.http.HttpServletResponse;

/**
 * 统一响应结果处理器，将接口返回值封装为Result对象
 * 支持通过 {@link NoWrap} 注解（类/方法级别）排除不需要包装的接口
 *
 * @author com.doumiot
 * @version 4.0
 */
@ControllerAdvice
@Slf4j
public class ReturnObjectHandler implements ResponseBodyAdvice<Object> {
    // 常量定义：2xx成功状态码范围
    private static final int HTTP_SUCCESS_START = HttpStatus.OK.value();
    private static final int HTTP_SUCCESS_END = HttpStatus.MULTIPLE_CHOICES.value();

    // Use the same getter-based contract as normal JSON responses, including Result.isSuccess().
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 判断是否需要对返回结果进行包装
     * 排除带有 {@link NoWrap} 的类或方法
     */
    @Override
    public boolean supports(MethodParameter methodParameter, Class<? extends HttpMessageConverter<?>> converterType) {
        boolean hasMethodAnnotation = methodParameter.hasMethodAnnotation(NoWrap.class);
        boolean hasClassAnnotation = methodParameter.getContainingClass().isAnnotationPresent(NoWrap.class);

        // 记录排除包装的日志（便于调试）
        if (hasMethodAnnotation || hasClassAnnotation) {
            String target = hasMethodAnnotation ? "方法" : "类";
            log.trace("{}带有@NoWrap注解，不进行结果包装：{}#{}",
                    target,
                    methodParameter.getContainingClass().getSimpleName(),
                    methodParameter.getMethod().getName());
        }
        return !hasMethodAnnotation && !hasClassAnnotation;
    }

    /**
     * 对返回结果进行包装处理
     */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter methodParameter, MediaType mediaType,
                                  Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        String requestPath = request.getURI().getPath();
        String methodInfo = getMethodInfo(methodParameter); // 安全获取方法信息

        // 安全获取响应状态码
        int statusCode = getResponseStatusCode(response);

        // 已包装为Result的对象处理
        if (body instanceof Result) {
            return handleExistingResult((Result) body, requestPath, methodInfo, response);
        }

        // 非成功状态码响应，且未包装result的，不进行包装
        if (!isSuccessStatusCode(statusCode)) {
            return body;
        }

        // 处理null值
        if (body == null) {
            log.trace("返回值为null, 自动包装成功响应, 路径: {}, 方法: {}", requestPath, methodInfo);
            return Result.success();
        }

        // 处理String类型（特殊处理避免类型转换问题）
        if (body instanceof String) {
            log.trace("String类型返回值包装, 路径: {}, 方法: {}", requestPath, methodInfo);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return handleStringResult((String) body, requestPath, methodInfo);
        }

        // 其他类型正常包装
        log.trace("常规类型返回值包装, 路径: {}, 方法: {}", requestPath, methodInfo);
        return Result.success(body);
    }

    /**
     * 处理已包装为Result的响应
     */
    private Result handleExistingResult(Result result, String requestPath, String methodInfo,
                                        ServerHttpResponse response) {
        // 成功响应直接返回
        if (Result.SUCCESSResultEnum.SUCCESS.code() == result.getCode()) {
            log.trace("已为成功Result对象, 不重复包装, 路径: {}, 方法: {}", requestPath, methodInfo);
            return result;
        }

        // 处理业务异常响应
        log.warn("业务异常响应, 路径: {}, 方法: {}, 错误码: {}, 消息: {}",
                requestPath, methodInfo, result.getCode(), result.getMessage());

        // 设置对应的HTTP状态码（优先使用业务映射，否则默认500）
        HttpStatus httpStatus = mapBusinessCodeToHttpStatus(result.getCode());
        response.setStatusCode(httpStatus);

        return result;
    }

    /**
     * 处理String类型的返回结果
     */
    private Object handleStringResult(String body, String requestPath, String methodInfo) {
        return handleStringResult(Result.success(body), requestPath, methodInfo);
    }

    /**
     * 处理String类型的Result对象序列化
     */
    private Object handleStringResult(Result result, String requestPath, String methodInfo) {

        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("String类型结果序列化失败, 路径: {}, 方法: {}", requestPath, methodInfo, e);
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    /**
     * 安全获取响应状态码
     */
    private int getResponseStatusCode(ServerHttpResponse response) {
        if (response instanceof ServletServerHttpResponse) {
            HttpServletResponse servletResponse = ((ServletServerHttpResponse) response).getServletResponse();
            return servletResponse.getStatus();
        }
        // 非Servlet环境默认返回200
        return HttpStatus.OK.value();
    }

    /**
     * 判断是否为成功状态码（2xx）
     */
    private boolean isSuccessStatusCode(int status) {
        return status >= HTTP_SUCCESS_START && status < HTTP_SUCCESS_END;
    }

    /**
     * 将业务错误码映射为HTTP状态码
     * 优先使用预定义映射，无匹配时返回默认200（业务正常）
     */
    private HttpStatus mapBusinessCodeToHttpStatus(int businessCode) {
        HttpStatus httpStatus = Result.getHttpStatus(businessCode);
        if (httpStatus == null) {
            return HttpStatus.OK;
        }
        return httpStatus;
    }

    /**
     * 安全获取方法信息（避免NPE）
     */
    private String getMethodInfo(MethodParameter methodParameter) {
        try {
            return methodParameter.getContainingClass().getSimpleName() + "#" +
                    methodParameter.getMethod().getName();
        } catch (Exception e) {
            return "unknown-method"; // 处理接口默认方法等特殊情况
        }
    }
}
