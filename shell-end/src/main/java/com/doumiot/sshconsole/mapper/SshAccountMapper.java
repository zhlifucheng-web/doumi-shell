package com.doumiot.sshconsole.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doumiot.sshconsole.entity.SshAccount;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SshAccountMapper extends BaseMapper<SshAccount> {
}
