package com.doumiot.spring.exception.handler;

import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.spring.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.validation.ConstraintViolationException;

/**
 * 全局异常处理器
 * 统一处理系统中未捕获的异常
 *
 * @author com.doumiot
 * @version 1.0
 * @since 2021-03-15
 */
@RestControllerAdvice
@Slf4j
@Order(Integer.MAX_VALUE -1)
public class GlobalExceptionHandler {

    /**
     * 处理业务运行时异常
     *
     * @param e 业务运行时异常
     * @return 错误结果
     */
    @ResponseBody
    @ExceptionHandler(value = BusinessRuntimeException.class)
    public Result businessExceptionHandler(BusinessRuntimeException e) {
        Result result = Result.failure(e.getException(), e.getMessage());
        log.debug("业务异常: {}", result, e);
        return result;
    }

    /**
     * 处理参数校验异常
     *
     * @param e 参数校验异常
     * @return 错误结果
     */
    @ResponseBody
    @ExceptionHandler(value = ConstraintViolationException.class)
    public Result paramsExceptionHandler(ConstraintViolationException e) {
        Result result = Result.failure(BusinessRuntimeException.ParamExceptionEnum.PARAM_IS_INVALID, e.getMessage());
        log.error("参数校验异常: {}", result, e);
        return result;
    }

    /**
     * 处理非法参数异常
     *
     * @param e 非法参数异常
     * @return 错误结果
     */
    @ResponseBody
    @ExceptionHandler(value = IllegalArgumentException.class)
    public Result IllegalArgumentExceptionHandler(IllegalArgumentException e) {
        Result result = Result.failure(BusinessRuntimeException.ParamExceptionEnum.INNER_PARAM_IS_INVALID, e.getMessage());
        log.error("非法参数异常: {}", result, e);
        return result;
    }
}
