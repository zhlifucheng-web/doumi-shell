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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@SshConsoleEnabled
@Service
public class SshTreeService {

    @Resource
    private SshGroupMapper sshGroupMapper;
    @Resource
    private SshHostMapper sshHostMapper;
    @Resource
    private SshAccountMapper sshAccountMapper;
    @Resource private SshGroupService groups;
    @Resource private SshHostService hosts;
    @Resource private SshAccountService accounts;
    @Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Transactional(rollbackFor = Exception.class)
    public void moveAndReorder(ReorderRequest request) {
        if (request == null || !StringUtils.hasText(request.getNodeType()) || request.getId() == null)
            throw invalid("拖拽节点参数无效");
        String type = request.getNodeType().trim().toUpperCase();
        Long oldParentId = currentParent(type, request.getId());
        Long newParentId = request.getParentId();
        if (!Objects.equals(oldParentId, newParentId)) {
            if ("GROUP".equals(type)) groups.move(request.getId(), newParentId);
            else if ("HOST".equals(type) && newParentId != null) hosts.move(request.getId(), newParentId);
            else if ("ACCOUNT".equals(type) && newParentId != null) accounts.move(request.getId(), newParentId);
            else throw invalid("目标父节点类型无效");
        }

        List<Long> destinationOrder = request.getOrderedIds();
        reorder(type, newParentId, destinationOrder, request.getId());
        if (!Objects.equals(oldParentId, newParentId)) {
            List<Long> sourceOrder = request.getSourceOrderedIds();
            reorder(type, oldParentId, sourceOrder, null);
        }
    }

    private void reorder(String type, Long parentId, List<Long> orderedIds, Long requiredId) {
        if (orderedIds == null) throw invalid("排序列表缺失");
        Set<Long> requested = new HashSet<>(orderedIds);
        if (requested.size() != orderedIds.size() || (requiredId != null && !requested.contains(requiredId)))
            throw invalid("排序列表包含重复或缺失节点");
        List<Long> actual = siblingIds(type, parentId);
        if (!requested.equals(new HashSet<>(actual))) throw invalid("分组内容已变化，请刷新后重试");
        String table = table(type);
        for (int index = 0; index < orderedIds.size(); index++) {
            jdbc.update("UPDATE " + table + " SET sort_order=? WHERE id=?", index, orderedIds.get(index));
        }
    }

    private Long currentParent(String type, Long id) {
        if ("GROUP".equals(type)) {
            SshGroup row = sshGroupMapper.selectById(id);
            if (row != null) return row.getParentId();
        } else if ("HOST".equals(type)) {
            SshHost row = sshHostMapper.selectById(id);
            if (row != null) return row.getGroupId();
        } else if ("ACCOUNT".equals(type)) {
            SshAccount row = sshAccountMapper.selectById(id);
            if (row != null) return row.getHostId();
        }
        throw invalid("拖拽节点不存在");
    }

    private List<Long> siblingIds(String type, Long parentId) {
        List<Long> ids = new ArrayList<>();
        if ("GROUP".equals(type)) {
            LambdaQueryWrapper<SshGroup> query = new LambdaQueryWrapper<SshGroup>().orderByAsc(SshGroup::getSortOrder).orderByAsc(SshGroup::getId);
            if (parentId == null) query.isNull(SshGroup::getParentId); else query.eq(SshGroup::getParentId, parentId);
            for (SshGroup row : sshGroupMapper.selectList(query)) ids.add(row.getId());
        } else if ("HOST".equals(type) && parentId != null) {
            for (SshHost row : sshHostMapper.selectList(new LambdaQueryWrapper<SshHost>().eq(SshHost::getGroupId, parentId))) ids.add(row.getId());
        } else if ("ACCOUNT".equals(type) && parentId != null) {
            for (SshAccount row : sshAccountMapper.selectList(new LambdaQueryWrapper<SshAccount>().eq(SshAccount::getHostId, parentId))) ids.add(row.getId());
        } else if (!"GROUP".equals(type)) throw invalid("目标父节点类型无效");
        return ids;
    }

    private String table(String type) {
        if ("GROUP".equals(type)) return "ssh_group";
        if ("HOST".equals(type)) return "ssh_host";
        if ("ACCOUNT".equals(type)) return "ssh_account";
        throw invalid("拖拽节点类型无效");
    }

    private BusinessRuntimeException invalid(String message) {
        return new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, message);
    }

    @lombok.Data
    public static class ReorderRequest {
        private String nodeType;
        private Long id;
        private Long parentId;
        private List<Long> orderedIds;
        private List<Long> sourceOrderedIds;
    }

    public List<Map<String, Object>> listSearchTargets() {
        return jdbc.queryForList("SELECT "
                + "h.id AS hostId, "
                + "h.name AS hostName, "
                + "h.host AS hostAddress, "
                + "h.port AS hostPort, "
                + "h.remark AS hostRemark, "
                + "h.group_id AS groupId, "
                + "g.name AS groupName, "
                + "a.id AS accountId, "
                + "a.username AS username, "
                + "a.remark AS accountRemark "
                + "FROM ssh_host h "
                + "LEFT JOIN ssh_account a ON a.host_id=h.id AND a.enabled=1 "
                + "LEFT JOIN ssh_group g ON h.group_id=g.id "
                + "WHERE h.enabled=1 "
                + "ORDER BY CASE WHEN g.name IS NULL THEN 1 ELSE 0 END, g.name, h.name, a.username");
    }

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
