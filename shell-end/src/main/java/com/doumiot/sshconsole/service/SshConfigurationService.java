package com.doumiot.sshconsole.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.entity.*;
import com.doumiot.sshconsole.mapper.*;
import com.doumiot.config.ConfigurationArchive;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;

@Service
@SshConsoleEnabled
public class SshConfigurationService {
    @Resource private SshGroupMapper groups;
    @Resource private SshHostMapper hosts;
    @Resource private SshAccountMapper accounts;
    @Resource private SshScriptMapper scripts;
    @Resource private SshGroupService groupService;
    @Resource private SshHostService hostService;
    @Resource private SshAccountService accountService;
    @Resource private SshScriptService scriptService;
    @Resource private SshScriptScheduleService schedules;
    @Resource private CredentialCipher cipher;
    @Resource private ObjectMapper json;

    @Data
    public static class BackupAccount extends SshAccountService.SshAccountSaveRequest {
        private Long id;
    }
    @Data
    public static class Snapshot {
        private List<SshGroup> groups = new ArrayList<>();
        private List<SshHost> hosts = new ArrayList<>();
        private List<BackupAccount> accounts = new ArrayList<>();
        private List<SshScriptService.SshScriptVO> scripts = new ArrayList<>();
    }

    @Transactional(readOnly = true)
    public ConfigurationArchive.Archive exportAll(String password, boolean encrypted) throws Exception {
        Snapshot snapshot = new Snapshot();
        snapshot.setGroups(groups.selectList(null));
        snapshot.setHosts(hosts.selectList(null));
        for (SshAccount account : accounts.selectList(null)) {
            BackupAccount saved = json.convertValue(accountService.getById(account.getId()), BackupAccount.class);
            saved.setSecret(accountService.loadSecret(account));
            snapshot.getAccounts().add(saved);
        }
        snapshot.setScripts(scriptService.listForHost(null));
        byte[] bytes = json.writeValueAsBytes(snapshot);
        return encrypted ? ConfigurationArchive.encrypt(bytes, password) : ConfigurationArchive.plain(bytes);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Integer> importAll(ConfigurationArchive.Archive archive, String password) throws Exception {
        Snapshot snapshot = json.readValue(ConfigurationArchive.decrypt(archive, password), Snapshot.class);
        if (snapshot.getGroups() == null || snapshot.getHosts() == null || snapshot.getAccounts() == null
                || snapshot.getScripts() == null) throw new IllegalArgumentException("配置列表不能为空");
        Map<Long, Long> groupIds = new HashMap<>(), hostIds = new HashMap<>(), accountIds = new HashMap<>();
        Set<Long> scriptIds = new HashSet<>();
        String suffix = " (导入 " + UUID.randomUUID().toString().substring(0, 8) + ")";
        List<SshGroup> pending = new ArrayList<>(snapshot.getGroups());
        while (!pending.isEmpty()) {
            boolean progressed = false;
            Iterator<SshGroup> iterator = pending.iterator();
            while (iterator.hasNext()) {
                SshGroup old = iterator.next();
                if (old == null || old.getId() == null || groupIds.containsKey(old.getId()))
                    throw new IllegalArgumentException("分组 ID 缺失或重复");
                if (!org.springframework.util.StringUtils.hasText(old.getName()))
                    throw new IllegalArgumentException("分组名称不能为空");
                if (old.getParentId() != null && !groupIds.containsKey(old.getParentId())) continue;
                SshGroupService.SshGroupSaveRequest request = json.convertValue(old, SshGroupService.SshGroupSaveRequest.class);
                request.setParentId(old.getParentId() == null ? null : groupIds.get(old.getParentId()));
                if (old.getParentId() == null) request.setName(old.getName() + suffix);
                groupIds.put(old.getId(), groupService.create(request).getId());
                iterator.remove();
                progressed = true;
            }
            if (!progressed) throw new IllegalArgumentException("分组存在循环或父分组缺失");
        }
        for (SshHost old : snapshot.getHosts()) {
            assertNewId(old == null ? null : old.getId(), hostIds);
            SshHostService.SshHostSaveRequest request = json.convertValue(old, SshHostService.SshHostSaveRequest.class);
            request.setGroupId(reference(groupIds, old.getGroupId(), "主机所属分组"));
            hostIds.put(old.getId(), hostService.create(request).getId());
        }
        for (BackupAccount old : snapshot.getAccounts()) {
            assertNewId(old == null ? null : old.getId(), accountIds);
            Long sourceId = old.getId();
            old.setHostId(reference(hostIds, old.getHostId(), "账户所属主机"));
            accountIds.put(sourceId, accountService.create(old).getId());
        }
        for (SshScriptService.SshScriptVO old : snapshot.getScripts()) {
            if (old == null || old.getId() == null || !scriptIds.add(old.getId()))
                throw new IllegalArgumentException("脚本 ID 缺失或重复");
            SshScriptService.SshScriptSaveRequest request = json.convertValue(old, SshScriptService.SshScriptSaveRequest.class);
            request.setHostId(old.getHostId() == null ? null : reference(hostIds, old.getHostId(), "脚本所属主机"));
            if (request.getSchedule() != null) {
                request.getSchedule().setAccountId(reference(accountIds, request.getSchedule().getAccountId(), "定时执行账户"));
                request.getSchedule().setEnabled(false);
            }
            scriptService.create(request);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("groups", groupIds.size()); counts.put("hosts", hostIds.size());
        counts.put("accounts", accountIds.size()); counts.put("scripts", scriptIds.size());
        return counts;
    }

    private static void assertNewId(Long id, Map<Long, Long> ids) {
        if (id == null || ids.containsKey(id)) throw new IllegalArgumentException("配置 ID 缺失或重复");
    }
    private static Long reference(Map<Long, Long> ids, Long source, String label) {
        if (source == null || !ids.containsKey(source)) throw new IllegalArgumentException(label + "不存在");
        return ids.get(source);
    }
}
