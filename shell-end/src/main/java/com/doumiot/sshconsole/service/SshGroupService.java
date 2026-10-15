package com.doumiot.sshconsole.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.entity.SshGroup;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.mapper.SshGroupMapper;
import com.doumiot.sshconsole.mapper.SshHostMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@SshConsoleEnabled
@Service
public class SshGroupService {

    @Resource
    private SshGroupMapper sshGroupMapper;
    @Resource
    private SshHostMapper sshHostMapper;

    public SshGroup create(SshGroupSaveRequest req) {
        validateName(req.getName());
        assertSiblingNameUnique(req.getParentId(), req.getName(), null);
        if (req.getParentId() != null && sshGroupMapper.selectById(req.getParentId()) == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "父分组不存在");
        }
        SshGroup row = new SshGroup();
        row.setParentId(req.getParentId());
        row.setName(req.getName().trim());
        row.setRemark(trimToNull(req.getRemark()));
        row.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        sshGroupMapper.insert(row);
        return row;
    }

    public SshGroup update(long id, SshGroupSaveRequest req) {
        SshGroup existing = requireGroup(id);
        validateName(req.getName());
        Long newParentId = req.getParentId();
        if (newParentId != null && newParentId.equals(id)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "分组不能以自己为父级");
        }
        if (newParentId != null) {
            if (sshGroupMapper.selectById(newParentId) == null) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "父分组不存在");
            }
            assertNotDescendantParent(id, newParentId);
        }
        assertSiblingNameUnique(newParentId, req.getName(), id);
        existing.setParentId(newParentId);
        existing.setName(req.getName().trim());
        existing.setRemark(trimToNull(req.getRemark()));
        if (req.getSortOrder() != null) {
            existing.setSortOrder(req.getSortOrder());
        }
        sshGroupMapper.updateById(existing);
        return existing;
    }

    public SshGroup move(long id, Long parentId) {
        SshGroupSaveRequest req = new SshGroupSaveRequest();
        req.setParentId(parentId);
        req.setName(requireGroup(id).getName());
        req.setRemark(requireGroup(id).getRemark());
        req.setSortOrder(requireGroup(id).getSortOrder());
        return update(id, req);
    }

    public SshGroup getById(long id) {
        return requireGroup(id);
    }

    public int delete(long id) {
        requireGroup(id);
        Long childGroups = sshGroupMapper.selectCount(
                new LambdaQueryWrapper<SshGroup>().eq(SshGroup::getParentId, id));
        if (childGroups != null && childGroups > 0) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "请先删除子分组");
        }
        Long hosts = sshHostMapper.selectCount(new LambdaQueryWrapper<SshHost>().eq(SshHost::getGroupId, id));
        if (hosts != null && hosts > 0) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "请先删除分组下的主机");
        }
        return sshGroupMapper.deleteById(id);
    }

    public void assertSiblingNameUnique(Long parentId, String name, Long excludeId) {
        LambdaQueryWrapper<SshGroup> q = new LambdaQueryWrapper<>();
        if (parentId == null) {
            q.isNull(SshGroup::getParentId);
        } else {
            q.eq(SshGroup::getParentId, parentId);
        }
        q.eq(SshGroup::getName, name.trim());
        if (excludeId != null) {
            q.ne(SshGroup::getId, excludeId);
        }
        Long count = sshGroupMapper.selectCount(q);
        if (count != null && count > 0) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "同级分组名称已存在");
        }
    }

    void assertNotDescendantParent(long groupId, long newParentId) {
        Set<Long> descendants = collectDescendantIds(groupId);
        if (descendants.contains(newParentId)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "不能将分组移动到其子孙分组下");
        }
    }

    private Set<Long> collectDescendantIds(long rootId) {
        Set<Long> ids = new HashSet<>();
        collectChildren(rootId, ids);
        return ids;
    }

    private void collectChildren(long parentId, Set<Long> ids) {
        for (SshGroup g : sshGroupMapper.selectList(
                new LambdaQueryWrapper<SshGroup>().eq(SshGroup::getParentId, parentId))) {
            ids.add(g.getId());
            collectChildren(g.getId(), ids);
        }
    }

    SshGroup requireGroup(long id) {
        SshGroup g = sshGroupMapper.selectById(id);
        if (g == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "分组不存在");
        }
        return g;
    }

    private static void validateName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "分组名称不能为空");
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
    public static class SshGroupSaveRequest {
        private Long parentId;
        private String name;
        private String remark;
        private Integer sortOrder;
    }
}
