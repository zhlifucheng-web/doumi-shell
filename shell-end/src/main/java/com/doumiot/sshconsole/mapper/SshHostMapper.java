package com.doumiot.sshconsole.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doumiot.sshconsole.entity.SshHost;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SshHostMapper extends BaseMapper<SshHost> {
}
