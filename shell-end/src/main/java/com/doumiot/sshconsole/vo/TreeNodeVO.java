package com.doumiot.sshconsole.vo;

import lombok.Data;

import java.util.Map;

@Data
public class TreeNodeVO {
    private String nodeType;
    private Long id;
    private String key;
    private String label;
    private String remark;
    private Boolean leaf;
    private Map<String, Object> extra;
}
