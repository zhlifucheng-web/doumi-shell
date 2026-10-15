package com.doumiot.spring.result;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一结果返回类
 * 用于封装接口返回结果，统一格式
 *
 * @author LiFucheng
 * @since 2023-07-22
 */
public class Result {
    /**
     * gson
     */
    private static final Gson gson = new GsonBuilder().enableComplexMapKeySerialization().create();
    /**
     * 业务错误码与HTTP状态码的映射关系（可根据实际业务扩展）
     * 键：业务错误码，值：对应的HTTP状态码
     */
    private static final Map<Integer, HttpStatus> BUSINESS_CODE_TO_HTTP_STATUS = new ConcurrentHashMap<>();

    /**
     * 添加业务错误码与HTTP状态码的映射关系
     * 键：业务错误码，值：对应的HTTP状态码
     * 注意：业务错误码必须唯一，不能重复
     * 通过该方法添加映射关系，可以覆盖默认的映射关系，使前台可以更标准的处理异常
     *
     * @param businessCode 业务错误码
     * @param httpStatus   HTTP状态码
     */
    public static void addBusinessCodeToHttpStatusMapping(int businessCode, HttpStatus httpStatus) {
        BUSINESS_CODE_TO_HTTP_STATUS.put(businessCode, httpStatus);
    }

    public static HttpStatus getHttpStatus(int businessCode) {
        return BUSINESS_CODE_TO_HTTP_STATUS.get(businessCode);
    }

    /**
     * 成功结果枚举
     */
    public enum SUCCESSResultEnum implements ResultEnum {
        SUCCESS(200, "请求成功");

        private final int code;
        private final String message;

        /**
         * 构造函数
         *
         * @param code    代码
         * @param message 消息
         */
        SUCCESSResultEnum(int code, String message) {
            this.code = code;
            this.message = message;
        }

        @Override
        public String type() {
            return SUCCESSResultEnum.class.getName();
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
     * 代码
     */
    private int code;
    /**
     * 异常类型
     */
    private String exceptionType = "";
    /**
     * 消息
     */
    private String message = "";
    /**
     * 外部消息
     */
    private String externMessage = "";
    /**
     * 业务数据；使用 Object 由 Jackson 与 HTTP 响应一并序列化，避免先 Gson 成字符串再被二次转义为 JSON 字符串。
     */
    private Object data;

    /**
     * 获取代码
     *
     * @return 代码
     */
    public int getCode() {
        return code;
    }

    /**
     * 设置结果代码
     *
     * @param resultEnum 结果枚举
     */
    public void setResultCode(ResultEnum resultEnum) {
        this.code = resultEnum.code();
        this.message = resultEnum.message();
        this.exceptionType = resultEnum.type();
    }

    /**
     * 获取消息
     *
     * @return 消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取数据
     *
     * @return 数据
     */
    public Object getData() {
        return data;
    }

    /**
     * 设置数据
     *
     * @param data 数据
     */
    public void setData(Object data) {
        this.data = data;
    }

    /**
     * 获取异常类型
     *
     * @return 异常类型
     */
    public String getExceptionType() {
        return exceptionType;
    }

    /**
     * 获取外部消息
     *
     * @return 外部消息
     */
    public String getExternMessage() {
        return externMessage;
    }

    @Override
    public String toString() {
        return "Result{" +
                "code=" + code +
                ", exceptionType='" + exceptionType + '\'' +
                ", message='" + message + '\'' +
                ", externMessage='" + externMessage + '\'' +
                ", data=" + data +
                '}';
    }

    /**
     *  判断是否成功
     * @return
     */
    public boolean isSuccess() {
        return code == SUCCESSResultEnum.SUCCESS.code();
    }
    /**
     * 创建成功结果
     *
     * @return 成功结果
     */
    public static Result success() {
        Result result = new Result();
        result.setResultCode(SUCCESSResultEnum.SUCCESS);
        return result;
    }

    /**
     * 创建带数据的成功结果
     *
     * @param data 数据
     * @return 成功结果
     */
    public static <T> Result success(T data) {
        Result result = new Result();
        result.setResultCode(SUCCESSResultEnum.SUCCESS);
        if (data != null) {
            result.setData(data);
        }
        return result;
    }

    /**
     * 创建失败结果
     *
     * @param resultEnum 结果枚举
     * @return 失败结果
     */
    public static Result failure(ResultEnum resultEnum) {
        Result result = new Result();
        result.setResultCode(resultEnum);
        return result;
    }

    /**
     * 创建带外部消息的失败结果
     *
     * @param resultEnum    结果枚举
     * @param externMessage 外部消息
     * @return 失败结果
     */
    public static <T> Result failure(ResultEnum resultEnum, T externMessage) {
        Result result = new Result();
        result.setResultCode(resultEnum);
        if (externMessage != null) {
            if (externMessage instanceof String) {
                result.externMessage = (String) externMessage;
                return result;
            }
            result.externMessage = gson.toJson(externMessage);
        }
        return result;
    }

    /**
     * 结果枚举接口
     */
    public interface ResultEnum {
        String type();

        int code();

        String message();
    }
}
