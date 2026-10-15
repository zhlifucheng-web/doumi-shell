package com.doumiot.sshconsole.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.entity.SshAccount;
import com.doumiot.sshconsole.entity.SshGroup;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.mapper.SshAccountMapper;
import com.doumiot.sshconsole.mapper.SshGroupMapper;
import com.doumiot.sshconsole.mapper.SshHostMapper;
import com.doumiot.sshconsole.model.TreeParentType;
import com.doumiot.sshconsole.vo.TreeNodeVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SshConsoleEnabled
@Service
public class SshTreeService {

    @Resource
    private SshGroupMapper sshGroupMapper;
    @Resource
    private SshHostMapper sshHostMapper;
    @Resource
    private SshAccountMapper sshAccountMapper;

    public List<TreeNodeVO> listChildren(String parentType, Long parentId) {
        if (!StringUtils.hasText(parentType)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "parentType 必填");
        }
        String type = parentType.trim().toUpperCase();
        if (TreeParentType.ROOT.equals(type)) {
            return listRootGroups();
        }
        if (TreeParentType.GROUP.equals(type)) {
            if (parentId == null) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "parentId 必填");
            }
            return listGroupChildren(parentId);
        }
        if (TreeParentType.HOST.equals(type)) {
            if (parentId == null) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "parentId 必填");
            }
            return listHostAccounts(parentId);
        }
        throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "parentType 无效");
    }

    private List<TreeNodeVO> listRootGroups() {
        List<SshGroup> groups = sshGroupMapper.selectList(
                new LambdaQueryWrapper<SshGroup>()
                        .isNull(SshGroup::getParentId)
                        .orderByAsc(SshGroup::getSortOrder, SshGroup::getId));
        List<TreeNodeVO> nodes = new ArrayList<>();
        for (SshGroup g : groups) {
            nodes.add(groupNode(g));
        }
        return nodes;
    }

    private List<TreeNodeVO> listGroupChildren(long groupId) {
        if (sshGroupMapper.selectById(groupId) == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "分组不存在");
        }
        List<TreeNodeVO> nodes = new ArrayList<>();
        List<SshGroup> childGroups = sshGroupMapper.selectList(
                new LambdaQueryWrapper<SshGroup>()
                        .eq(SshGroup::getParentId, groupId)
                        .orderByAsc(SshGroup::getSortOrder, SshGroup::getId));
        for (SshGroup g : childGroups) {
            nodes.add(groupNode(g));
        }
        List<SshHost> hosts = sshHostMapper.selectList(
                new LambdaQueryWrapper<SshHost>()
                        .eq(SshHost::getGroupId, groupId)
                        .orderByAsc(SshHost::getSortOrder, SshHost::getId));
        for (SshHost h : hosts) {
            nodes.add(hostNode(h));
        }
        return nodes;
    }

    private List<TreeNodeVO> listHostAccounts(long hostId) {
        if (sshHostMapper.selectById(hostId) == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "主机不存在");
        }
        List<SshAccount> accounts = sshAccountMapper.selectList(
                new LambdaQueryWrapper<SshAccount>()
                        .eq(SshAccount::getHostId, hostId)
                        .orderByAsc(SshAccount::getSortOrder, SshAccount::getId));
        if (accounts.isEmpty()) {
            return Collections.emptyList();
        }
        List<TreeNodeVO> nodes = new ArrayList<>();
        for (SshAccount a : accounts) {
            nodes.add(accountNode(a));
        }
        return nodes;
    }

    private static TreeNodeVO groupNode(SshGroup g) {
        TreeNodeVO n = new TreeNodeVO();
        n.setNodeType("GROUP");
        n.setId(g.getId());
        n.setKey("g:" + g.getId());
        n.setLabel(g.getName());
        n.setRemark(g.getRemark());
        n.setLeaf(false);
        n.setExtra(Collections.emptyMap());
        return n;
    }

    private static TreeNodeVO hostNode(SshHost h) {
        TreeNodeVO n = new TreeNodeVO();
        n.setNodeType("HOST");
        n.setId(h.getId());
        n.setKey("h:" + h.getId());
        n.setLabel(h.getName());
        n.setRemark(h.getRemark());
        n.setLeaf(false);
        Map<String, Object> extra = new HashMap<>();
        int port = h.getPort() != null ? h.getPort() : 22;
        extra.put("endpoint", h.getHost() + ":" + port);
        extra.put("host", h.getHost());
        extra.put("port", port);
        extra.put("groupId", h.getGroupId());
        extra.put("enabled", h.getEnabled());
        n.setExtra(extra);
        return n;
    }

    private static TreeNodeVO accountNode(SshAccount a) {
        TreeNodeVO n = new TreeNodeVO();
        n.setNodeType("ACCOUNT");
        n.setId(a.getId());
        n.setKey("a:" + a.getId());
        n.setLabel(a.getUsername());
        n.setRemark(a.getRemark());
        n.setLeaf(true);
        Map<String, Object> extra = new HashMap<>();
        extra.put("authType", a.getAuthType());
        extra.put("hostId", a.getHostId());
        extra.put("enabled", a.getEnabled());
        n.setExtra(extra);
        return n;
    }
}
