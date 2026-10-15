package com.doumiot.spring.exception;

import com.doumiot.spring.result.Result;

/**
 * 业务运行时异常类
 * 用于封装业务逻辑中的异常情况
 *
 * @author LiFucheng
 * @version 1.0
 * @since 2022-03-04
 */
public class BusinessRuntimeException extends RuntimeException {
    private final Result.ResultEnum exception;

    public BusinessRuntimeException(Result result) {
        this(new Result.ResultEnum() {
            @Override
            public String type() {
                return result.getExceptionType();
            }

            @Override
            public int code() {
                return result.getCode();
            }

            @Override
            public String message() {
                return result.getMessage();
            }
        }, result.getExternMessage());
    }

    public BusinessRuntimeException(Result.ResultEnum exception) {
        this(exception, null);
    }

    public BusinessRuntimeException(Result.ResultEnum exception, String msg) {
        super(msg);
        this.exception = exception;
    }

    public Result.ResultEnum getException() {
        return exception;
    }

    /**
     * 服务异常枚举类
     * 定义系统级别的异常类型
     *
     * @author com.doumiot
     * @version 1.0
     * @since 2021-03-15
     */
    public enum ServerExceptionEnum implements Result.ResultEnum {
        // 统一命名风格
        API_ERROR(-4, "API异常"),
        GENERAL_ERROR(-1, "未处理的全局服务器异常 Exception"),
        PROPERTY_VALIDATE_ERROR(-2, "属性校验未通过"),
        QPS_ERROR(-3, "异常访问"),
        SERIALIZATION_ERROR(-5, "序列化异常");

        private final int code;
        private final String message;

        ServerExceptionEnum(int code, String message) {
            this.code = code;
            this.message = message;
        }

        @Override
        public int code() {
            return code;
        }

        @Override
        public String message() {
            return message;
        }

        @Override
        public String type() {
            return ServerExceptionEnum.class.getName();
        }
    }

    /**
     * 参数异常枚举类
     * 定义参数校验相关的异常类型
     *
     * @author com.doumiot
     * @version 1.0
     * @since 2021-03-15
     */
    public enum ParamExceptionEnum implements Result.ResultEnum {
        PARAM_IS_INVALID(-100, "方法入参参数校验异常"),
        INNER_PARAM_IS_INVALID(-101, "业务中参数异常");

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

    /**
     * 数据库操作异常枚举类
     * 定义数据库操作相关的异常类型
     *
     * @author LiFucheng
     * @version 1.0
     * @since 2022-03-04
     */
    public enum SQLExceptionEnum implements Result.ResultEnum {
        INSERT_ERROR(-200, "数据插入失败"),
        DELETE_ERROR(-201, "数据删除失败"),
        SELECT_ERROR(-202, "数据查找失败"),
        UPDATE_ERROR(-203, "数据更新失败");

        private final int code;
        private final String message;

        SQLExceptionEnum(int code, String message) {
            this.code = code;
            this.message = message;
        }

        @Override
        public String type() {
            return SQLExceptionEnum.class.getName();
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
}
