package com.doumiot.common.objects.dto;

import lombok.Data;

/**
 * 基础请求参数DTO，使用gateway网关注入到请求中的方式，将请求参数注入到方法参数中,如果不是通过gateway网关的形式，这些参数就为空。
 */
@Data
public class BaseRequestDTO {
    private String traceId;
    private String ipAddress;
    private Long requestTime;
}
