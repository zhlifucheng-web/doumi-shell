package com.doumiot.sshconsole.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ssh_script")
public class SshScript {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /** null = 全局脚本 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long hostId;
    private String name;
    private String interpreter;
    private String content;
    /** null 或空：在登录用户家目录执行 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String workDir;
    private String remark;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
