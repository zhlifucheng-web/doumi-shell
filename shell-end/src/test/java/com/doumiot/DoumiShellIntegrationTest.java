package com.doumiot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.doumiot.ShellEndApplication;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.doumiot.sshconsole.entity.*;
import com.doumiot.sshconsole.service.*;
import com.doumiot.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ShellEndApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"doumi.storage.data-dir=./target/integration-data/${random.uuid}",
                "doumi.ssh-console.credential-master-key=", "logging.level.root=WARN"})
class DoumiShellIntegrationTest {
    @Autowired SshGroupService groups;
    @Autowired SshHostService hosts;
    @Autowired SshAccountService accounts;
    @Autowired SshScriptService scripts;
    @Autowired SshScriptScheduleService schedules;
    @Autowired SshConfigurationService configuration;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TestRestTemplate http;
    @Autowired CredentialCipher cipher;
    @Autowired SshScriptExecutionService executions;
    @Autowired SshDataCleanupService cleanup;

    @Test void entryAndVersionAreNeverCachedAndDesktopLaunchUsesMatchingVersion() {
        org.springframework.http.ResponseEntity<String> page=http.getForEntity("/",String.class);
        assertEquals(200,page.getStatusCodeValue());
        assertTrue(page.getHeaders().getCacheControl().contains("no-store"));
        org.springframework.http.ResponseEntity<Map> version=http.getForEntity("/version.json",Map.class);
        assertEquals(200,version.getStatusCodeValue());
        assertTrue(version.getHeaders().getCacheControl().contains("no-store"));
        assertEquals("http://localhost/?v="+version.getBody().get("version"),ApplicationVersion.browserUrl("http://localhost/"));
    }

    private long[] assets() {
        SshGroupService.SshGroupSaveRequest group = new SshGroupService.SshGroupSaveRequest();
        group.setName("test-" + UUID.randomUUID());
        SshGroup savedGroup = groups.create(group);
        SshHostService.SshHostSaveRequest host = new SshHostService.SshHostSaveRequest();
        host.setName("test-host"); host.setHost("127.0.0.1"); host.setPort(1); host.setGroupId(savedGroup.getId());
        SshHost savedHost = hosts.create(host);
        SshAccountService.SshAccountSaveRequest account = new SshAccountService.SshAccountSaveRequest();
        account.setHostId(savedHost.getId()); account.setUsername("tester"); account.setAuthType("PASSWORD");
        CredentialCipher.SshSecretPayload secret = new CredentialCipher.SshSecretPayload();
        secret.setPassword("roundtrip-secret"); account.setSecret(secret);
        return new long[]{savedGroup.getId(), savedHost.getId(), accounts.create(account).getId()};
    }

    @Test void backupRemapsReferencesAndReencryptsCredentialsAndPausesSchedules() throws Exception {
        long[] ids = assets();
        SshScriptService.SshScriptSaveRequest script = new SshScriptService.SshScriptSaveRequest();
        script.setName("backup-script"); script.setContent("echo hello"); script.setHostId(ids[1]);
        SshScriptScheduleService.Schedule schedule = new SshScriptScheduleService.Schedule();
        schedule.setAccountId(ids[2]); schedule.setCron("0 0 1 1 *"); schedule.setEnabled(true);
        script.setSchedule(schedule);
        long sourceId = scripts.create(script).getId();
        ConfigurationArchive.Archive archive = configuration.exportAll("test-password", true);
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM ssh_script", Integer.class);
        Map<String, Integer> imported = configuration.importAll(archive, "test-password");
        assertEquals(1, imported.get("groups"));
        assertEquals(1, imported.get("hosts"));
        assertEquals(1, imported.get("accounts"));
        assertEquals(1, imported.get("scripts"));
        assertEquals(before * 2, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_script", Integer.class));
        Long importedScriptId = jdbc.queryForObject("SELECT MAX(id) FROM ssh_script WHERE name='backup-script'", Long.class);
        assertNotEquals(sourceId, importedScriptId);
        SshScriptService.SshScriptVO restored = scripts.getById(importedScriptId);
        assertNotEquals(ids[1], restored.getHostId());
        assertFalse(restored.getSchedule().isEnabled());
        assertNotEquals(ids[2], restored.getSchedule().getAccountId());
        byte[] encrypted = jdbc.queryForObject("SELECT secret_enc FROM ssh_account WHERE id=?", byte[].class,
                restored.getSchedule().getAccountId());
        assertEquals("roundtrip-secret", CredentialCipher.parseSecretJson(cipher.decryptToJson(encrypted)).getPassword());
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group", Integer.class);
        assertThrows(IllegalArgumentException.class, () -> configuration.importAll(archive, "wrong-password"));
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group", Integer.class));
    }

    @Test void deletingHostCascadesAccountsScriptsAndSchedulesButKeepsExecutionHistory() {
        long[] ids = assets();
        SshScriptService.SshScriptSaveRequest request = new SshScriptService.SshScriptSaveRequest();
        request.setName("host-cascade-script"); request.setContent("echo hello"); request.setHostId(ids[1]);
        SshScriptScheduleService.Schedule schedule = new SshScriptScheduleService.Schedule();
        schedule.setAccountId(ids[2]); schedule.setCron("0 0 1 1 *"); request.setSchedule(schedule);
        long scriptId = scripts.create(request).getId();
        String executionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ssh_script_execution(id,script_id,script_name,account_id,host_id,source,status,started_at) VALUES(?,?,?, ?,?,'MANUAL','FAILED','2000-01-01T00:00:00Z')",
                executionId, scriptId, "host-cascade-script", ids[2], ids[1]);

        assertEquals(1, hosts.delete(ids[1]));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_host WHERE id=?", Integer.class, ids[1]));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_account WHERE host_id=?", Integer.class, ids[1]));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_script WHERE host_id=?", Integer.class, ids[1]));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_script_schedule WHERE script_id=? OR account_id=?", Integer.class, scriptId, ids[2]));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_script_execution WHERE id=?", Integer.class, executionId));
        assertNotNull(groups.getById(ids[0]));
    }

    @Test void deletingGroupCascadesNestedGroupsHostsAccountsScriptsAndKeepsExecutionHistory() {
        long[] ids = assets();
        SshGroupService.SshGroupSaveRequest childRequest = new SshGroupService.SshGroupSaveRequest();
        childRequest.setParentId(ids[0]); childRequest.setName("nested-" + UUID.randomUUID());
        long childId = groups.create(childRequest).getId();
        SshHostService.SshHostSaveRequest hostRequest = new SshHostService.SshHostSaveRequest();
        hostRequest.setGroupId(childId); hostRequest.setName("nested-host"); hostRequest.setHost("127.0.0.1");
        long hostId = hosts.create(hostRequest).getId();
        SshAccountService.SshAccountSaveRequest accountRequest = new SshAccountService.SshAccountSaveRequest();
        accountRequest.setHostId(hostId); accountRequest.setUsername("nested-user"); accountRequest.setAuthType("PASSWORD");
        CredentialCipher.SshSecretPayload secret = new CredentialCipher.SshSecretPayload(); secret.setPassword("nested-secret");
        accountRequest.setSecret(secret);
        long accountId = accounts.create(accountRequest).getId();
        SshScriptService.SshScriptSaveRequest scriptRequest = new SshScriptService.SshScriptSaveRequest();
        scriptRequest.setHostId(hostId); scriptRequest.setName("nested-script"); scriptRequest.setContent("echo nested");
        long scriptId = scripts.create(scriptRequest).getId();
        String executionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ssh_script_execution(id,script_id,script_name,account_id,host_id,source,status,started_at) VALUES(?,?,?,?,?,'MANUAL','FAILED','2000-01-01T00:00:00Z')",
                executionId, scriptId, "nested-script", accountId, hostId);

        assertEquals(2, groups.delete(ids[0]));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group WHERE id IN (?,?)", Integer.class, ids[0], childId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_host WHERE id IN (?,?)", Integer.class, ids[1], hostId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_account WHERE id IN (?,?)", Integer.class, ids[2], accountId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_script WHERE host_id IN (?,?)", Integer.class, ids[1], hostId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_script_execution WHERE id=?", Integer.class, executionId));
    }

    @Test void failedImportRollsBackAndInvalidSchedulesDoNotSaveScripts() throws Exception {
        SshConfigurationService.Snapshot snapshot = new SshConfigurationService.Snapshot();
        SshGroup valid = new SshGroup(); valid.setId(1L); valid.setName("valid");
        SshGroup broken = new SshGroup(); broken.setId(2L); broken.setParentId(999L); broken.setName("broken");
        snapshot.setGroups(Arrays.asList(valid, broken));
        ConfigurationArchive.Archive archive = ConfigurationArchive.encrypt(json.writeValueAsBytes(snapshot), "test-password");
        int groupsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group", Integer.class);
        assertThrows(IllegalArgumentException.class, () -> configuration.importAll(archive, "test-password"));
        assertEquals(groupsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group", Integer.class));
        SshScriptService.SshScriptSaveRequest request = new SshScriptService.SshScriptSaveRequest();
        request.setName("invalid"); request.setContent("echo bad");
        SshScriptScheduleService.Schedule schedule = new SshScriptScheduleService.Schedule();
        schedule.setEnabled(true); schedule.setCron("bad cron"); request.setSchedule(schedule);
        int scriptsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ssh_script", Integer.class);
        assertThrows(RuntimeException.class, () -> scripts.create(request));
        assertEquals(scriptsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM ssh_script", Integer.class));
    }

    @Test void schedulerRunsWithoutBrowserSessionAndStopsWhenDisabled() throws Exception {
        long[] ids = assets();
        SshScriptService.SshScriptSaveRequest request = new SshScriptService.SshScriptSaveRequest();
        request.setName("scheduled"); request.setContent("echo hello"); request.setHostId(ids[1]);
        SshScriptScheduleService.Schedule schedule = new SshScriptScheduleService.Schedule();
        schedule.setCron("*/1 * * * * *"); schedule.setAccountId(ids[2]); schedule.setEnabled(true);
        request.setSchedule(schedule);
        long id = scripts.create(request).getId();
        schedules.reconcile();
        long deadline = System.currentTimeMillis() + 8000;
        while (!"ERROR".equals(schedules.get(id).getLastStatus()) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertEquals("ERROR", schedules.get(id).getLastStatus()); // 测试端口拒绝连接，验证确实自动尝试 SSH
        schedule.setEnabled(false); scripts.update(id, request); schedules.reconcile();
        String last = schedules.get(id).getLastRunAt();
        Thread.sleep(1200);
        assertEquals(last, schedules.get(id).getLastRunAt());
        scripts.delete(id);
        assertNull(schedules.get(id));
    }

    @Test void rootApiAndCronPreviewAreAvailableWithoutContextPath() {
        Map<?, ?> response = http.postForObject("/api/ssh-console/scripts/cron-preview",
                Collections.singletonMap("cron", "0 9 * * *"), Map.class);
        assertEquals(200, response.get("code"));
        assertEquals(5, ((List<?>) response.get("data")).size());
    }
    @Test void manualFailuresAndCronHavePersistentHistoryAfterScriptDeletion() throws Exception {
        long[] ids=assets();
        SshScriptService.SshScriptSaveRequest request=new SshScriptService.SshScriptSaveRequest();
        request.setName("history-test");request.setContent("echo hello");request.setHostId(ids[1]);
        long script=scripts.create(request).getId();
        Map<String,Object> started=executions.start(script,ids[2],"MANUAL");String id=(String)started.get("id");
        long end=System.currentTimeMillis()+5000;
        while(Arrays.asList("QUEUED","RUNNING").contains(executions.detail(id).get("status"))&&System.currentTimeMillis()<end)Thread.sleep(50);
        Map<String,Object> row=executions.detail(id);
        assertEquals("ERROR",row.get("status"));assertNotNull(row.get("finished_at"));assertNotNull(row.get("error"));
        scripts.delete(script);
        assertEquals("history-test",executions.detail(id).get("script_name"));
        assertEquals(1L,executions.list(script,null,"MANUAL",1,20).get("total"));
        assertEquals("ERROR",executions.stop(id).get("status"));
    }

    private SshDataCleanupService.Request cleanupRequest(String... tables) {
        SshDataCleanupService.Request r=new SshDataCleanupService.Request();r.setTables(Arrays.asList(tables));
        r.setFrom("2000-01-01T00:00:00Z");r.setTo("2001-01-01T00:00:00Z");return r;
    }

    @Test void plaintextExportAndImportWorkWithoutPasswordAndEncryptionRemainsDefault() throws Exception {
        assets();
        org.springframework.http.ResponseEntity<Map> response=http.postForEntity("/api/ssh-console/configuration/export",
                Collections.singletonMap("encrypted",false),Map.class);
        assertEquals("no-store",response.getHeaders().getFirst("Cache-Control"));
        assertEquals(200,response.getBody().get("code"));
        Map<?,?> archive=(Map<?,?>)response.getBody().get("data");
        assertEquals("NONE",archive.get("algorithm"));
        assertTrue(json.writeValueAsString(archive.get("data")).contains("roundtrip-secret"));
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM ssh_account",Integer.class);
        Map<?,?> restored=http.postForObject("/api/ssh-console/configuration/import",Collections.singletonMap("archive",archive),Map.class);
        assertEquals(200,restored.get("code"));
        assertEquals(before*2,jdbc.queryForObject("SELECT COUNT(*) FROM ssh_account",Integer.class));
        Long imported=jdbc.queryForObject("SELECT MAX(id) FROM ssh_account",Long.class);
        byte[] secret=jdbc.queryForObject("SELECT secret_enc FROM ssh_account WHERE id=?",byte[].class,imported);
        assertEquals("roundtrip-secret",CredentialCipher.parseSecretJson(cipher.decryptToJson(secret)).getPassword());
        Map<?,?> defaultEncrypted=http.postForObject("/api/ssh-console/configuration/export",Collections.singletonMap("password","test-password"),Map.class);
        assertEquals(200,defaultEncrypted.get("code"));
        Map<?,?> encrypted=(Map<?,?>)defaultEncrypted.get("data");
        assertEquals("PBKDF2-SHA256/AES-256-GCM",encrypted.get("algorithm"));assertNotNull(encrypted.get("ciphertext"));assertNull(encrypted.get("data"));
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> preview(SshDataCleanupService.Request r) {return (Map<String,Object>)cleanup.preview(r);}

    @Test void cleanupProtectsUnselectedReferencesAndRangeAndRequiresFreshPreview() {
        long[] ids=assets();
        jdbc.update("UPDATE ssh_group SET created_at='2000-06-01 12:00:00' WHERE id=?",ids[0]);
        jdbc.update("UPDATE ssh_host SET created_at='2000-06-01 12:00:00' WHERE id=?",ids[1]);
        jdbc.update("UPDATE ssh_account SET created_at='2002-06-01 12:00:00' WHERE id=?",ids[2]);
        SshDataCleanupService.Request r=cleanupRequest("ssh_group","ssh_host","ssh_account");
        Map<String,Object> plan=preview(r);
        List<Map<String,Object>> counts=(List<Map<String,Object>>)plan.get("rows");
        assertEquals(0,counts.stream().mapToInt(c->((Number)c.get("deletable")).intValue()).sum());
        cleanup.clear((String)plan.get("token"));
        assertNotNull(accounts.getById(ids[2]));assertNotNull(hosts.getById(ids[1]));
        jdbc.update("UPDATE ssh_account SET created_at='2000-06-01 12:00:00' WHERE id=?",ids[2]);
        Map<String,Object> stale=preview(r);
        jdbc.update("UPDATE ssh_account SET created_at='2002-06-01 12:00:00' WHERE id=?",ids[2]);
        assertThrows(IllegalArgumentException.class,()->cleanup.clear((String)stale.get("token")));
        jdbc.update("UPDATE ssh_account SET created_at='2000-06-01 12:00:00' WHERE id=?",ids[2]);
        Map<String,Object> fresh=preview(r);cleanup.clear((String)fresh.get("token"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group WHERE id=?",Integer.class,ids[0]));
        assertThrows(IllegalArgumentException.class,()->cleanup.clear((String)fresh.get("token")));
        assertThrows(IllegalArgumentException.class,()->preview(cleanupRequest("ssh_account; DROP TABLE ssh_host")));
    }

    @Test void cleanupProtectsActiveExecutionsAndDoesNotCascadeSchedules() {
        long[] ids=assets();
        SshScriptService.SshScriptSaveRequest request=new SshScriptService.SshScriptSaveRequest();
        request.setName("protected-script");request.setContent("echo hello");request.setHostId(ids[1]);
        SshScriptScheduleService.Schedule schedule=new SshScriptScheduleService.Schedule();
        schedule.setAccountId(ids[2]);schedule.setCron("0 0 1 1 *");request.setSchedule(schedule);
        long script=scripts.create(request).getId();
        for(String table:Arrays.asList("ssh_script","ssh_account","ssh_host","ssh_group"))
            jdbc.update("UPDATE "+table+" SET created_at='2000-06-01 12:00:00' WHERE id=?",table.equals("ssh_script")?script:table.equals("ssh_account")?ids[2]:table.equals("ssh_host")?ids[1]:ids[0]);
        SshDataCleanupService.Request r=cleanupRequest("ssh_script","ssh_account","ssh_host","ssh_group");
        cleanup.clear((String)preview(r).get("token"));
        assertNotNull(scripts.getById(script));assertNotNull(schedules.get(script));
        String execution=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ssh_script_execution(id,script_id,account_id,host_id,source,status,started_at) VALUES(?,?,?,?,?,'RUNNING','2000-06-01T04:00:00Z')",execution,script,ids[2],ids[1],"MANUAL");
        r.setTables(Arrays.asList("ssh_script_execution","ssh_script_schedule","ssh_script","ssh_account","ssh_host","ssh_group"));
        jdbc.update("UPDATE ssh_script_schedule SET created_at='2000-06-01 12:00:00' WHERE script_id=?",script);
        cleanup.clear((String)preview(r).get("token"));
        assertNotNull(executions.detail(execution));assertNotNull(scripts.getById(script));assertNotNull(accounts.getById(ids[2]));
        jdbc.update("UPDATE ssh_script_execution SET status='SUCCESS' WHERE id=?",execution);
        cleanup.clear((String)preview(r).get("token"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ssh_host WHERE id=?",Integer.class,ids[1]));
        assertThrows(IllegalArgumentException.class,()->executions.detail(execution));
    }

    @Test void cleanupIncludesAiHistoryAndProtectsUnfinishedConversations() {
        long now=System.currentTimeMillis();
        String idle="ai-idle-"+UUID.randomUUID(),pending="ai-pending-"+UUID.randomUUID();
        byte[] messages=cipher.encryptSecretJson("[]"),events=cipher.encryptSecretJson("[]");
        for(String[] row:new String[][]{{idle,"idle"},{pending,"awaiting_confirmation"}})
            jdbc.update("INSERT INTO ai_conversation(id,provider_id,authorization_mode,state,messages_enc,events_enc,sequence,revision,created_at,updated_at) VALUES(?, 'test-provider', 'ask', ?, ?, ?, 0, 0, ?, ?)",row[0],row[1],messages,events,now,now);
        SshDataCleanupService.Request request=cleanupRequest("ai_conversation");request.setAllTime(true);
        Map<String,Object> plan=preview(request);
        List<Map<String,Object>> counts=(List<Map<String,Object>>)plan.get("rows");
        assertEquals(2,((Number)counts.get(0).get("matched")).intValue());
        assertEquals(1,((Number)counts.get(0).get("deletable")).intValue());
        assertEquals(1,((Number)counts.get(0).get("protected")).intValue());
        cleanup.clear((String)plan.get("token"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation WHERE id=?",Integer.class,idle));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation WHERE id=?",Integer.class,pending));
        jdbc.update("DELETE FROM ai_conversation WHERE id=?",pending);
    }

}
