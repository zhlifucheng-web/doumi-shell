package com.doumiot.sshconsole.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ssh_host")
public class SshHost {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long groupId;
    private String name;
    private String host;
    private Integer port;
    private String remark;
    private Integer sortOrder;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
