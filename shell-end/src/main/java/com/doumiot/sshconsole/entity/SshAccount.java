package com.doumiot.sshconsole.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.doumiot.mybatis.SqliteBlobByteArrayTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "ssh_account", autoResultMap = true)
public class SshAccount {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long hostId;
    private String username;
    private String authType;
    @TableField(value = "secret_enc", typeHandler = SqliteBlobByteArrayTypeHandler.class)
    private byte[] secretEnc;
    private String secretHint;
    private String defaultCwd;
    private String remark;
    private Integer sortOrder;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
