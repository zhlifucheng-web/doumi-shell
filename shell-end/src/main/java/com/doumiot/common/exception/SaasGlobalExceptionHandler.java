package com.doumiot.common.exception;

import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.spring.result.NoWrap;
import com.doumiot.spring.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * 统一处理系统中未捕获的异常
 *
 * @author com.doumiot
 * @version 1.0
 * @since 2021-03-15
 */
@NoWrap
@RestControllerAdvice
@Slf4j
@Order(Integer.MAX_VALUE - 1)
public class SaasGlobalExceptionHandler {

    /**
     * MethodArgumentNotValidException异常处理
     */
    @ResponseBody
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result exceptionHandler(MethodArgumentNotValidException e) {
        Result result = Result.failure(BusinessRuntimeException.ParamExceptionEnum.PARAM_IS_INVALID, e.getBindingResult().getFieldError().getDefaultMessage());
        log.info("接口参数验证异常: {}", result, e);
        return result;
    }
}