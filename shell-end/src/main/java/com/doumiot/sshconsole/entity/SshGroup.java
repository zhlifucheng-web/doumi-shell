package com.doumiot.sshconsole.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ssh_group")
public class SshGroup {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long parentId;
    private String name;
    private String remark;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
