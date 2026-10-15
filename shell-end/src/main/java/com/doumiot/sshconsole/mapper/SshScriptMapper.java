package com.doumiot.sshconsole.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doumiot.sshconsole.entity.SshScript;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SshScriptMapper extends BaseMapper<SshScript> {
}
