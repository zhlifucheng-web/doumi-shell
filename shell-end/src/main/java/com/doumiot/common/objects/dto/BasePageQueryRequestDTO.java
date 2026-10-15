package com.doumiot.common.objects.dto;

import lombok.Data;

@Data
public class BasePageQueryRequestDTO extends BaseRequestDTO {
    private Integer page = 1;
    private Integer size = 10;
    private String searchKey;
}