package com.doumiot.common.exception;

import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.spring.result.Result;
import com.doumiot.common.exception.enums.ParamExceptionEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

/**
 * 兜底全局异常处理器
 * 统一处理系统中未捕获的异常
 *
 * @author com.doumiot
 * @version 1.0
 * @since 2021-03-15
 */
@RestControllerAdvice
@Slf4j
public class BackstopGlobalExceptionHandler {

    private static final String MULTIPART_UPLOAD_HINT =
            "请使用 POST，Content-Type 为 multipart/form-data（不要设为 application/json），表单字段 file 上传 ZIP 二进制；"
                    + "fetch/axios 请使用 FormData 且勿覆盖 Content-Type，由浏览器自动带 boundary。";

    /**
     * 文件上传接口被以 JSON 等方式调用时的常见错误，给出可操作说明
     */
    @ResponseBody
    @ExceptionHandler({MultipartException.class, HttpMediaTypeNotSupportedException.class})
    public Result multipartExceptionHandler(Exception e) {
        log.warn("multipart/媒体类型异常: {}", e.getMessage());
        return Result.failure(BusinessRuntimeException.ParamExceptionEnum.INNER_PARAM_IS_INVALID, MULTIPART_UPLOAD_HINT);
    }

    /**
     * Spring 在 Content-Type 非 multipart 时可能抛出 IllegalStateException（文案含 multipart）
     */
    @ResponseBody
    @ExceptionHandler(IllegalStateException.class)
    public Result illegalStateForMultipart(IllegalStateException e) {
        String msg = e.getMessage();
        if (msg != null && msg.toLowerCase().contains("multipart")) {
            log.warn("非 multipart 请求: {}", msg);
            return Result.failure(BusinessRuntimeException.ParamExceptionEnum.INNER_PARAM_IS_INVALID, MULTIPART_UPLOAD_HINT);
        }
        return Result.failure(BusinessRuntimeException.ServerExceptionEnum.GENERAL_ERROR, msg);
    }

    /**
     * 处理通用异常
     *
     * @param e 通用异常
     * @return 错误结果
     */
    @ResponseBody
    @ExceptionHandler(value = Exception.class)
    public Result exceptionHandler(Exception e) {
        Throwable disconnect = findSshDisconnect(e);
        if (disconnect != null) {
            log.debug("SSH 连接在请求期间断开: {}", disconnect.getMessage());
            return Result.failure(ParamExceptionEnum.IN_PARAM_EMPTY,
                    "SSH 会话已断开，请重新连接");
        }
        Result result = Result.failure(BusinessRuntimeException.ServerExceptionEnum.GENERAL_ERROR, e.getMessage());
        log.error("系统未处理异常: {}", result, e);
        return result;
    }

    private static Throwable findSshDisconnect(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String type = cause.getClass().getSimpleName();
            String message = cause.getMessage();
            if ("Disconnected".equals(type)
                    || (message != null && ("disconnected".equalsIgnoreCase(message.trim())
                    || ("ConnectionException".equals(type)
                    && message.toLowerCase().contains("disconnected"))))) {
                return cause;
            }
        }
        return null;
    }
}
