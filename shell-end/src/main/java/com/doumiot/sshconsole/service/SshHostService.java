package com.doumiot.sshconsole.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.entity.SshAccount;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.mapper.SshAccountMapper;
import com.doumiot.sshconsole.mapper.SshGroupMapper;
import com.doumiot.sshconsole.mapper.SshHostMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;

@SshConsoleEnabled
@Service
public class SshHostService {

    @Resource
    private SshHostMapper sshHostMapper;
    @Resource
    private SshGroupMapper sshGroupMapper;
    @Resource
    private SshAccountMapper sshAccountMapper;
    public SshHost create(SshHostSaveRequest req) {
        validate(req);
        if (sshGroupMapper.selectById(req.getGroupId()) == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "分组不存在");
        }
        SshHost row = new SshHost();
        apply(row, req);
        sshHostMapper.insert(row);
        return row;
    }

    public SshHost update(long id, SshHostSaveRequest req) {
        SshHost existing = requireHost(id);
        validate(req);
        if (sshGroupMapper.selectById(req.getGroupId()) == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "分组不存在");
        }
        apply(existing, req);
        sshHostMapper.updateById(existing);
        return existing;
    }

    public SshHost move(long id, long groupId) {
        SshHost h = requireHost(id);
        SshHostSaveRequest req = new SshHostSaveRequest();
        req.setGroupId(groupId);
        req.setName(h.getName());
        req.setHost(h.getHost());
        req.setPort(h.getPort());
        req.setRemark(h.getRemark());
        req.setSortOrder(h.getSortOrder());
        req.setEnabled(h.getEnabled());
        return update(id, req);
    }

    public SshHost getById(long id) {
        return requireHost(id);
    }

    public int delete(long id) {
        requireHost(id);
        Long accounts = sshAccountMapper.selectCount(
                new LambdaQueryWrapper<SshAccount>().eq(SshAccount::getHostId, id));
        if (accounts != null && accounts > 0) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "请先删除主机下的账户");
        }
        return sshHostMapper.deleteById(id);
    }

    SshHost requireHost(long id) {
        SshHost h = sshHostMapper.selectById(id);
        if (h == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "主机不存在");
        }
        return h;
    }

    private void apply(SshHost row, SshHostSaveRequest req) {
        row.setGroupId(req.getGroupId());
        row.setName(req.getName().trim());
        row.setHost(req.getHost().trim());
        row.setPort(req.getPort() != null ? req.getPort() : 22);
        row.setRemark(trimToNull(req.getRemark()));
        row.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        row.setEnabled(req.getEnabled() != null ? req.getEnabled() : 1);
    }

    private static void validate(SshHostSaveRequest req) {
        if (req.getGroupId() == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "groupId 必填");
        }
        if (!StringUtils.hasText(req.getName())) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "主机展示名不能为空");
        }
        if (!StringUtils.hasText(req.getHost())) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "host 不能为空");
        }
        if (req.getPort() != null && (req.getPort() < 1 || req.getPort() > 65535)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "port 无效");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @lombok.Data
    public static class SshHostSaveRequest {
        private Long groupId;
        private String name;
        private String host;
        private Integer port;
        private String remark;
        private Integer sortOrder;
        private Integer enabled;
    }
}
