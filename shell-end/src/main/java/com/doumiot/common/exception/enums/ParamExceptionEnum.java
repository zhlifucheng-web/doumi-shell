package com.doumiot.common.exception.enums;

import com.doumiot.spring.result.Result;

/**
 * @author LiFucheng
 * @version 1.0
 * @date 2022/3/4 18:42
 */
public enum ParamExceptionEnum implements Result.ResultEnum {
    PARAM_NOT_EXIST(3000, "系统参数不存在"),
    IN_PARAM_EMPTY(3001, "入参不能为空"),
    ;
    private final int code;
    private final String message;

    ParamExceptionEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String type() {
        return ParamExceptionEnum.class.getName();
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}